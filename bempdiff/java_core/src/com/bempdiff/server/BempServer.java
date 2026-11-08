package com.bempdiff.server;

import com.bempdiff.ai.AiAnalyzer;
import com.bempdiff.ai.FileAnalysis;
import com.bempdiff.ai.HttpAiAnalyzer;
import com.bempdiff.ai.MockAiAnalyzer;
import com.bempdiff.ai.StageASummary;
import com.bempdiff.ai.context.ProjectContext;
import com.bempdiff.ai.context.ProjectContextCache;
import com.bempdiff.ai.context.ProjectIndex;
import com.bempdiff.ai.context.ProjectContextService;
import com.bempdiff.config.AiConfig;
import com.bempdiff.config.ParseConfig;
import com.bempdiff.decompile.Decompiler;
import com.bempdiff.diff.DiffEngine;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.diff.AstNormalizer;
import com.bempdiff.diff.FrontendTextDiff;
import com.bempdiff.diff.LibJarDiff;
import com.bempdiff.diff.ArchiveTree;
import com.bempdiff.export.AssetExporter;
import com.bempdiff.fs.FileOps;
import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.parse.FolderParser;
import com.bempdiff.parse.PackageParser;
import com.bempdiff.report.MarkdownReport;
import com.bempdiff.unpack.NestedUnpacker;
import com.bempdiff.unpack.UnpackOptions;
import com.bempdiff.unpack.UnpackReport;
import com.bempdiff.unpack.UnpackOutputer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Web UI 后端：内嵌 HTTP 服务（JDK 自带 com.sun.net.httpserver，零新增依赖）。
 * 绑定 127.0.0.1，同源托管静态 SPA；API 端点复用现有核心引擎（解析/差异/反编译/报告/导出/AI），
 * 不引入任何第三方 Web / JSON 框架。
 */
public final class BempServer {
    private static final Logger LOG = Logger.getLogger(BempServer.class.getName());

    // 重复字符串字面量常量：同一字符串在多处复用，集中定义避免重复。
    private static final String HDR_CONTENT_TYPE = "Content-Type";
    private static final String KEY_ERROR = "error";
    private static final String KEY_STATUS = "status";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_JOB_ID = "jobId";
    private static final String M_OPTIONS = "OPTIONS";
    private static final String MODE_FOLDER = "folder";
    private static final String STAGE_PARSING = "parsing";
    private static final String STAGE_UNPACKING = "unpacking";
    private static final String MSG_NOT_DONE = "比对尚未完成: ";
    private static final String EXPORT_ZIP_NAME = "bempdiff-export.zip";
    private static final String DEFAULT_INDEX = "/index.html";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_PROJECT_DIR = "projectDir";
    private static final String PROP_USER_HOME = "user.home";
    private static final String DIR_BEMPDIFF = ".bempdiff";
    private static final String DIR_AI_REPLAY = "ai_replay";
    private static final String DIR_RUNTIME = "runtime"; // 物理解包运行时目录（.bempdiff/runtime/<jobId>）
    private static final String STAGE_THINKING = "thinking";
    private static final String AI_MODEL_FIELD = "model";
    private static final String RISK_MEDIUM = "MEDIUM";
    // S1192：以下字符串在多处重复，集中定义为常量消除重复字面量。
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_FILES = "files";
    private static final String KEY_LAST_ERROR = "lastError";
    private static final String KEY_REFRESH = "refresh";
    private static final String MIME_ZIP = "application/zip";
    private static final String EXPORT_TEMP_PREFIX = "bempdiff-export-";
    // P1-2 增量导出内容缓存：同 job 的增量 zip 由不可变 diff/snap 幂等派生，二次导出直接复用，跳过重复 exportIncrement+zipTree（报告 §9 P1-2）。
    // 目录 / 前缀设计为与 EXPORT_TEMP_PREFIX 相邻的常量，方便统一调整导出缓存位置。
    private static final String EXPORT_CACHE_SUBDIR = "_export_cache";
    private static final String EXPORT_CACHE_FILE_PREFIX = "job-";
    private static final String CAT_OVERALL = "整体风险分析";
    private static final String CAT_BREAKING = "breaking";
    private static final String CAT_IMPACT = "impact";
    private static final String CAT_TESTPOINTS = "testpoints";
    private static final String CAT_CUSTOM = "custom";
    private static final String MSG_TASK_NOT_FOUND = "任务不存在: ";

    /** 单文件「AI功能总结」聚焦指令：真实调用与 token 预估共用同一字面量，
     *  避免两处复制后单边修改导致预估与实际 prompt 漂移（评审 M2）。 */
    private static final String SINGLE_FILE_FOCUS =
            "本次为单文件功能总结：请仅围绕该文件的差异内容，说明其功能定位、改动意图、"
                    + "风险与影响范围，并给出针对该文件的测试要点。不要提及其他文件或整体统计。";

    private final JobStore store = new JobStore();
    private final ServerConfig config;
    private final AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000);
    private final Path webroot;
    // 比对任务后台执行器（虚拟线程：每个比对一个轻量线程，互不阻塞；HTTP 处理函数仅负责提交与返回）。
    // 并发比对上限：避免用户连续提交大包时多个解析任务同时跑爆文件句柄/内存（虚拟线程本身无界）。
    private static final int MAX_CONCURRENT_COMPARES =
            Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
    private final Semaphore compareSem = new Semaphore(MAX_CONCURRENT_COMPARES);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    // 成本闸门：AI 入口 token 预估缓存（per-job），避免重复反编译；DONE 后 diff 不可变，可安全复用。
    private final Map<String, Map<String, Object>> aiEstimateCache = new ConcurrentHashMap<>();
    // per-job 的 AI 工作缓存（反编译类图 + 前端文本差异 + 依赖 jar 分析）。
    // report / ai-analyze / export 三入口共用，避免并行发起多个分析类别时 N 倍重复反编译（评审 P1 #8）。
    // T00427：ConcurrentHashMap+超限全清 改 LRU（保留热点，消除全清抖动），容量语义不变。
    private final LruMap<String, AiWorkCache> aiWorkCache = new LruMap<>(AI_WORK_CACHE_MAX);
    private static final int AI_WORK_CACHE_MAX = 16; // LRU 容量上限（原「超限清空」阈值沿用）
    private static final int ARCHIVE_TREE_CACHE_MAX = 64; // P0-B LRU 容量上限（原「超限清空」阈值沿用）

    // P0-B 归档展开结果 per-job 缓存：键 = jobId + NUL + 归档key；handleEntryChildren/recursive 共享。
    // 原因：这两端点每次请求都重新读归档 zip、重建树（报告 §8#2 的 IO 型慢点，p50 ~870ms、p95 ~1.5s），
    // 而同一 job 内同一归档反复展开结果不变——缓存在 job 内幂等，命中后跳过全部归档 IO。
    // 值可为 List(children) 或 Map(recursive)，sendJson 透传；job 内 ignoreExtensions 固定，键无需含忽略规则。
    // T00427：归档展开缓存同样改 LRU（容量语义不变）。
    private final LruMap<String, Object> archiveTreeCache = new LruMap<>(ARCHIVE_TREE_CACHE_MAX);

    // 差异资产导出：记录 + 单线程后台执行器。小包同步流式、大包异步生成（避免大导出阻塞 UI/HTTP 线程）。
    private final Map<String, ExportRecord> exports = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService exportPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // P1-2 增量导出内容缓存（jobId → 已生成的 zip 绝对路径）。需与 exports 下载目录分开管理：
    // exports 是「大包异步生成 + 下载管理」生命周期记录；这里只缓存「同步小包已生成过的增量 zip」，
    // 由不可变 diff/snap 派生故幂等，二次同步导出直接复用，避免重复 exportIncrement+zipTree（报告 §8#4 / §9 P1-2）。
    private final Map<String, Path> exportZipCache = new ConcurrentHashMap<>();

    /** 差异资产导出记录（下载管理页数据源）。 */
    private static final class ExportRecord {
        final String id;
        final String jobId;
        String filename;
        String status = "queued"; // queued / running / done / error
        long estimatedBytes;
        long size;
        long createdAt = System.currentTimeMillis();
        long finishedAt;
        String message = "";
        int incrementCount;
        int deletedCount;
        volatile Object lock = this; // NOSONAR S3077 - 供外部 synchronized(record.lock) 用作持有者锁；仅单次赋值(=this)、原子读写，volatile 即可保证可见性
        ExportRecord(String id, String jobId) { this.id = id; this.jobId = jobId; }
    }

    /** P0-C 静态资源缓存条目：文件字节 + 惰性校验所需元数据（内容类型/修改时间/大小）。 */
    private static final class StaticEntry {
        final byte[] data;
        final String contentType;
        final long lastModified;
        final long size;
        StaticEntry(byte[] data, String contentType, long lastModified, long size) {
            this.data = data;
            this.contentType = contentType;
            this.lastModified = lastModified;
            this.size = size;
        }
    }
    private static final int STATIC_CACHE_MAX = 256; // P0-C LRU 容量上限（原「超限清空」阈值沿用；静态资源不可变，逐出后懒重建即可）
    // P0-C 静态资源内存缓存：键 = URI 相对路径。原因：webroot 下静态资源不可变，每次整文件 readAll
    // 再回传是纯 IO（报告 §8#3：static-index p50 ~98ms、p95 ~247ms）；物化到内存后重复请求不再读盘，
    // 仅用零成本 stat（mtime+size）校验文件是否变化（开发热替换时不喂陈旧数据）。T00427 改 LRU。
    private final LruMap<String, StaticEntry> staticCache = new LruMap<>(STATIC_CACHE_MAX);

    public BempServer(ServerConfig config, Path webroot) {
        this.config = config;
        this.webroot = webroot;
        purgeRuntimeStale(); // 启动即清残留（崩溃/强杀遗留），避免 .bempdiff/runtime 长期堆积
        // 退出自动全量清理：进程正常退出时把「解压/抽取临时文件 + 遗留运行时目录 + 过期日志」一次性回收。
        // 原因：多数系统临时件仅靠 deleteOnExit 兜底，进程被强杀/长驻不重启时进程内清理永不触发
        // （磁盘爆满根因）；退出 hook 为这些场景补上确定性的进程退出清理。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { cleanupTempFiles(); } catch (Exception ignored) { /* 退出清理尽力而为，不阻断退出 */ }
        }, "bempdiff-exit-cleanup"));
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/session/compare", this::handleCompare);
        server.createContext("/api/tasks/running", this::handleRunningTasks); // 桌面壳关闭前查询进行中任务，防误关直接中断长比对
        server.createContext("/api/job", this::handleJob);
        server.createContext("/api/export", this::handleExportApi);
        server.createContext("/api/entry", this::handleEntry);
        server.createContext("/api/file", this::handleFile);
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/ai/test", this::handleAiTest);
        server.createContext("/api/ai/models", this::handleAiModels);
        server.createContext("/api/ai/context", this::handleAiContext);
        server.createContext("/api/admin/cleanup", this::handleCleanupTemp); // 配置中心「手动清理」
        server.createContext("/api/admin/diag-bundle", this::handleDiagBundle); // FR-011 一键导出脱敏诊断包
        server.createContext("/api/update/check", this::handleUpdateCheck); // 「关于」页版本更新检查
        server.createContext("/", this::handleStatic);
        // 关键：默认 HttpServer 用单线程串行处理所有请求——一次长比对会阻塞全部 API/静态资源。
        // 改为每个请求一个虚拟线程，比对任务再下沉到 workers 执行器，UI 才能边比对边轮询进度。
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info(() -> "[Web] BempDiff HTTP 服务已启动：http://127.0.0.1:" + port + "  (仅本机)");
        System.out.println("BempDiff Web 服务已启动: http://127.0.0.1:" + port); // NOSONAR - CLI 需在控制台可见
        // 阻塞主线程（CLI 子命令特性）
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- 工具 ----------------

    private static String readBody(HttpExchange ex) throws IOException {
        byte[] b = readAll(ex.getRequestBody());
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", HDR_CONTENT_TYPE);
    }

    private static void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        addCors(ex);
        byte[] b = Json.write(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add(HDR_CONTENT_TYPE, "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void sendText(HttpExchange ex, int code, String mime, String text) throws IOException {
        addCors(ex);
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add(HDR_CONTENT_TYPE, mime + "; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** 流式下发文件（zip 等大文件）：逐块写，避免整文件读入内存导致大响应体传输中断/断连。 */
    private static void sendFile(HttpExchange ex, int code, String mime, Path file) throws IOException {
        // 先打开句柄再取长度：若清理线程恰好删除该文件，已打开的句柄在 Windows/POSIX 上仍能读到最后，
        // 避免「已声明 Content-Length、打开时文件却不存在」导致客户端等满 60s。
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            addCors(ex);
            ex.getResponseHeaders().add(HDR_CONTENT_TYPE, mime);
            ex.getResponseHeaders().add("Content-Disposition", "attachment");
            ex.sendResponseHeaders(code, size);
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Channels.newInputStream(ch); OutputStream os = ex.getResponseBody()) {
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (IOException e) {
                // 响应头已发出，无法再补发错误体；关闭固定长度流会让 HttpServer 直接断连
                //（而不是让客户端挂到 60s）。只记录，不向上抛给调用方触发 sendError。
                LOG.log(Level.FINE, "sendFile 传输中断: " + file, e);
            }
        }
    }

    // 静态文件内联返回（不挂 Content-Disposition: attachment，否则浏览器会下载而非渲染）。
    private static void sendBytesInline(HttpExchange ex, int code, String mime, byte[] data) throws IOException {
        addCors(ex);
        ex.getResponseHeaders().add(HDR_CONTENT_TYPE, mime);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ERROR, msg);
        sendJson(ex, code, m);
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            if (pair.substring(0, idx).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(idx + 1);
                }
            }
        }
        return null;
    }

    private static String findJava() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) {
            Path cand = Paths.get(jh, "bin", "java.exe");
            if (cand.toFile().isFile()) return cand.toString();
            Path cand2 = Paths.get(jh, "bin", "java");
            if (cand2.toFile().isFile()) return cand2.toString();
        }
        return "java";
    }

    private Path cfrPath(CompareOptions opts) {
        if (opts.getCfrJar() == null || opts.getCfrJar().isEmpty()) return null;
        return Paths.get(opts.getCfrJar());
    }

    // ---------------- 比对 ----------------

    private void handleCompare(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        try {
            Map<String, Object> req = Json.parseObject(readBody(ex));
            String leftType = Json.str(req, "leftType", "package");
            String leftPath = Json.str(req, "leftPath", null);
            String rightPath = Json.str(req, "rightPath", null);
            if (leftPath == null || rightPath == null) {
                sendError(ex, 400, "缺少 leftPath / rightPath");
                return;
            }
            CompareOptions opts = CompareOptions.fromRequest(req);
            ParseConfig pc = opts.toParseConfig();
            final String mode = MODE_FOLDER.equals(leftType) ? MODE_FOLDER : "package";

            // T03669：输入校验前置——路径不存在 / 类型不匹配时在「建作业」之前直接 400，
            // 不进入解包与比对流程。此前缺失文件会一路走到 PackageParser 抛异常，错误只以
            // 3~5 秒的 toast 出现，toast 消失后界面回落成与「两个包完全一致」无法区分的空态。
            String inputErr = validateCompareInput(leftPath, rightPath, mode);
            if (inputErr != null) {
                sendError(ex, 400, inputErr);
                return;
            }

            // T02741：尊重用户在「左框=老包 / 右框=新包」的显式放置，不再按文件名版本号自动重排。
            // 旧逻辑会自动按版本升序交换 left/right，当用户放置方向与版本号不符时会出现「对比栏方向反了」。
            // 现在 leftPath/rightPath 完全由用户决定（runCompareTask 中 oldSnap=左、newSnap=右），
            // 左栏即老包快照、右栏即新包快照，方向始终与用户选择一致。
            // autoOrdered 保留字段但恒为 false（不再自动重排）；oldVersion/newVersion 仅作文件名版号展示，
            // 真正的状态栏/详情版号来自快照（状态接口的 oldSnap/newSnap 版本），与用户放置方向一致。
            boolean autoOrdered = false;
            String oldVersion = com.bempdiff.parse.PackageVersion.extractFromFileName(leftPath);
            String newVersion = com.bempdiff.parse.PackageVersion.extractFromFileName(rightPath);

            String jobId = "job-" + seq.incrementAndGet();
            Job job = new Job(jobId, mode, opts);
            store.put(job);

            // 后台异步执行（解析 + 差异计算可能很慢）：提交即返回 jobId，前端轮询 /api/job/{id}/status 取进度/结果。
            final String fLeftPath = leftPath;
            final String fRightPath = rightPath;
            workers.submit(() -> runCompareTask(job, leftType, fLeftPath, fRightPath, pc, opts));

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put(KEY_JOB_ID, jobId);
            resp.put("mode", mode);
            resp.put(KEY_STATUS, job.getStatus());
            resp.put("progress", job.getProgress());
            resp.put(KEY_PHASE, job.getPhase());
            resp.put(KEY_MESSAGE, job.getMessage());
            // 智能识别结果：供前端提示「已按版本自动排序 旧 vX → 新 vY」
            resp.put("autoOrdered", autoOrdered);
            resp.put("oldVersion", oldVersion);
            resp.put("newVersion", newVersion);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "比对提交失败", e);
            sendError(ex, 500, "比对提交失败: " + e.getMessage());
        }
    }

    /**
     * 比对输入存在性校验（T03669）：返回 null 表示可以开始比对，否则返回面向用户的原因短句。
     * 只做「能不能比」的判断，不做任何解析。原因文案不带「比对失败」前缀——
     * 前缀由界面统一添加，两层都加会出现「比对失败：比对失败: …」的重复。
     */
    public static String validateCompareInput(String leftPath, String rightPath, String mode) {
        String leftErr = validateOneInput(leftPath, "老包", mode);
        return leftErr != null ? leftErr : validateOneInput(rightPath, "新包", mode);
    }

    private static String validateOneInput(String raw, String label, String mode) {
        Path p;
        try {
            p = Paths.get(raw);
        } catch (RuntimeException e) {
            return label + "路径不合法：" + raw;
        }
        boolean folderMode = MODE_FOLDER.equals(mode);
        if (!Files.exists(p)) {
            return label + (folderMode ? "目录不存在：" : "文件不存在：") + p.toAbsolutePath();
        }
        if (folderMode && !Files.isDirectory(p)) {
            return label + "不是目录：" + p.toAbsolutePath();
        }
        if (!folderMode && Files.isDirectory(p)) {
            return label + "是目录，请改用目录对比：" + p.toAbsolutePath();
        }
        return null;
    }

    /** 把比对异常翻译成不带「比对失败」前缀的原因短句（前缀由界面统一加，避免重复）。 */
    public static String describeCompareFailure(Exception e) {
        if (e instanceof java.io.FileNotFoundException) {
            return "文件不存在：" + e.getMessage();
        }
        if (e instanceof java.nio.file.NoSuchFileException) {
            return "文件不存在：" + ((java.nio.file.NoSuchFileException) e).getFile();
        }
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    /** 后台比对任务：解析 → 差异计算 → 构建统计，按阶段推进进度；阶段边界检查取消请求。 */
    private void runCompareTask(Job job, String leftType, String leftPath, String rightPath,
                                ParseConfig pc, CompareOptions opts) {
        try {
            compareSem.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            job.fail("比对启动被中断");
            return;
        }
        try {
            // 等待并发许可期间可能已被取消（仍 QUEUED），拿到许可后立即再判一次，避免空跑一整轮解析。
            if (job.isCancelRequested()) { job.markCancelled(); return; }
            // 开始新比对：回收此前其它作业的解包运行时目录（磁盘只保留当前作业的解包产物，防垃圾累积）。
            sweepSupersededJobs(job);
            job.markRunning(STAGE_PARSING, "解析包（旧）…", 10);
            PackageSnapshot oldSnap;
            PackageSnapshot newSnap;
            if (MODE_FOLDER.equals(leftType)) {
                FolderParser fp = new FolderParser();
                oldSnap = fp.parse(Paths.get(leftPath), pc);
                job.markRunning(STAGE_PARSING, "解析包（新）…", 30);
                newSnap = fp.parse(Paths.get(rightPath), pc);
            } else {
                PackageParser pp = new PackageParser();
                oldSnap = pp.parse(Paths.get(leftPath), pc, opts.isExpandAll());
                job.markRunning(STAGE_PARSING, "解析包（新）…", 30);
                newSnap = pp.parse(Paths.get(rightPath), pc, opts.isExpandAll());
            }
            // M-A：物理平铺解包（unpackNested 开启时）：对两侧快照内的 ARCHIVE/JAR 条目多线程递归
            // 展开并平面化，使差异统计与后续 AI 覆盖嵌套子文件。flatten 在本方法内同步完成
            // （invokeAll.get 带超时），随后的 job.complete() 即构成"AI 待解包完成"硬屏障。
            UnpackReport repOld = null;
            UnpackReport repNew = null;
            if (opts.isUnpackNested()) {
                UnpackOptions uo = opts.toUnpackOptions();
                // 解包临时目录改为作业级：.bempdiff/runtime/<jobId>/old|new，随作业被取代/淘汰整体回收。
                Path jobRuntime = ensureJobRuntimeDir(job);
                // 作业级 memo 预算：本作业两侧共用一份，与被 sweep 保留的回看作业互不挤兑，
                // 修复「二次比对极慢」（旧全局单预算被上一作业吃满 → 全量原子退化为逐文件落盘）。
                NestedUnpacker.MemoBudget memoBudget = new NestedUnpacker.MemoBudget();
                job.markRunning(STAGE_UNPACKING, "正在逐层解包（旧侧）…", 33);
                repOld = new UnpackReport(leftType);
                oldSnap = new NestedUnpacker(uo, jobRuntime.resolve("old"), memoBudget)
                        .flatten(oldSnap, repOld, unpackProgress(job, "旧侧"));
                job.markRunning(STAGE_UNPACKING, "正在逐层解包（新侧）…", 37);
                repNew = new UnpackReport(leftType);
                newSnap = new NestedUnpacker(uo, jobRuntime.resolve("new"), memoBudget)
                        .flatten(newSnap, repNew, unpackProgress(job, "新侧"));
            }
            job.setUnpackReports(new UnpackReport[]{repOld, repNew});
            // 解包状态报告与错误日志落盘（{user.home}/.bempdiff/logs/unpack-<jobId>.json|.log），
            // 供离线排查；写失败不中断主流程（openLog 式容错）。
            if (repOld != null || repNew != null) { // NOSONAR S2589 - 报表由运行时解包开关决定，可能为 null 也可能非 null，流分析误判恒定假
                UnpackOutputer.write(job.id, job.getUnpackReports(), unpackLogsDir());
            }
            job.markRunning(STAGE_PARSING, "解析完成", 40);
            if (job.isCancelRequested()) { job.markCancelled(); return; }

            job.markRunning("diffing", "计算差异…", 50);
            DiffEngine engine = new DiffEngine();
            DiffResult r = engine.compute(oldSnap, newSnap);
            job.markRunning("diffing", "计算差异…", 70);
            // 全量叶子统计：解包后快照已含所有嵌套归档内部差异，故直接 engine.stats(r)，
            // 使「差异数字」等于最终逐层解包后的差异数量，与自动解包展开后的差异树一致。
            // 原因：此前在自动解包下用容器级 topLevelStats，数字固定为初始/折叠口径，
            // 解包展开树变大后数字不随之更新，与最终解包后的差异数量不一致（用户反馈）。
            DiffStats s = engine.stats(r);
            if (job.isCancelRequested()) { job.markCancelled(); return; }

            job.markRunning("building", "构建差异树…", 90);
            job.complete(oldSnap, newSnap, r, s);
            // 解包不完整 / 存在条目读取失败超限：非致命，但差异/导出可能不完整，透出到 message 供前端提示。
            if (opts.isUnpackNested()) {
                appendUnpackWarnings(job, repOld, repNew);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "比对失败: " + job.id);
            job.fail(describeCompareFailure(e));
        } finally {
            compareSem.release();
        }
    }

    /** 解包不完整/读取失败超限时向 job message 追加告警（提取自 runCompareTask 以降低认知复杂度）。 */
    private static void appendUnpackWarnings(Job job, com.bempdiff.unpack.UnpackReport repOld,
                                             com.bempdiff.unpack.UnpackReport repNew) {
        if (repOld == null && repNew == null) return; // 未启用解包时两侧均为 null，无需追加
        int fail = ((repOld == null) ? 0 : repOld.getErrors().size())
                + ((repNew == null) ? 0 : repNew.getErrors().size());
        boolean incomplete = (repOld != null && repOld.isIncomplete())
                || (repNew != null && repNew.isIncomplete());
        if (incomplete || fail > 0) {
            job.appendMessage("警告：解包不完整（" + fail + " 个条目读取失败/超限"
                    + (incomplete ? "，且解包未全部完成" : "") + "），部分嵌套差异可能缺失");
        }
    }

    private Map<String, Object> statsJson(DiffStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("added", s.getAdded());
        m.put("deleted", s.getDeleted());
        m.put("modified", s.getModified());
        m.put("unchanged", s.getUnchanged());
        m.put("bizChanged", s.getBizChanged());
        m.put("jarChanged", s.getJarChanged());
        // total = 并集数（新增+删除+修改+未变）：与新/旧两快照的差异分类合并口径一致，
        // 与顶部全局汇总徽标合计、差异树节点总数同源，避免"条目总数"与徽标(如 86/84/2/24617)对不上的困惑。
        m.put("total", s.getAdded() + s.getDeleted() + s.getModified() + s.getUnchanged());
        return m;
    }

    private List<Object> buildTree(DiffResult r, PackageSnapshot oldSnap, PackageSnapshot newSnap, boolean unpackNested) {
        List<Object> tree = new ArrayList<>();
        DiffStatus[] order = {DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED, DiffStatus.UNCHANGED};
        for (DiffStatus st : order) {
            for (String k : r.get(st)) {
                // 版本改名配对的 MODIFIED 条目：以「新侧 key」展示（新包包名 + 其下文件路径），
                // 而非旧侧（如 bemp-web-5...M.15.war 应显示为 M.17.war）；内容层已支持按新 key 反查旧侧。
                if (st == DiffStatus.MODIFIED) {
                    String nk = r.newKeyFor(k);
                    if (nk != null) k = nk;
                }
                // 自动解包去重：仅当本次作业启用了物理解包时才跳过扁平化嵌套后代——避免误藏
                // 未解包模式下已存在的嵌套条目（如 expandAll 正常生成、非平铺的带归档祖先路径）。
                if (unpackNested && isFlattenedDescendant(k, oldSnap, newSnap)) continue;
                tree.add(buildNode(k, oldSnap, newSnap, st, r));
            }
        }
        return tree;
    }

    /** 判断 key 是否位于某个归档容器（ARCHIVE/JAR）之下（即被 NestedUnpacker 物理平铺的嵌套后代）。
     * 嵌套内部键形如 …/web.zip!/WEB-INF/…：按 '/' 切分出的祖先前缀可能带尾 '!'，
     * 需去掉该 '!' 才对应真正的归档容器键。 */
    private static boolean isFlattenedDescendant(String key, PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        int idx = key.indexOf('/');
        while (idx >= 0) {
            String prefix = key.substring(0, idx);
            if (isArchiveContainer(prefix, oldSnap) || isArchiveContainer(prefix, newSnap)) return true;
            if (prefix.endsWith("!")) {
                String cont = prefix.substring(0, prefix.length() - 1); // 去尾 '!' → 归档容器键
                if (isArchiveContainer(cont, oldSnap) || isArchiveContainer(cont, newSnap)) return true;
            }
            idx = key.indexOf('/', idx + 1);
        }
        return false;
    }

    private static boolean isArchiveContainer(String key, PackageSnapshot snap) {
        if (snap == null) return false;
        LogicalEntry e = snap.getEntries().get(key);
        if (e == null) return false;
        FileClass fc = e.getFileClass();
        return fc == FileClass.ARCHIVE || fc == FileClass.JAR;
    }

    private static Map<String, Object> buildNode(String k, PackageSnapshot oldSnap, PackageSnapshot newSnap,
                                                  DiffStatus st, DiffResult r) {
        LogicalEntry e = oldSnap.getEntries().get(k);
        if (e == null) e = newSnap.getEntries().get(k);
        LogicalEntry oe = oldSnap.getEntries().get(k);
        LogicalEntry ne = newSnap.getEntries().get(k);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", k);
        node.put(KEY_STATUS, st.name());
        node.put("layer", (e != null && e.getLayer() != null) ? e.getLayer().name() : "?");
        node.put("fileClass", (e != null && e.getFileClass() != null) ? e.getFileClass().name() : "OTHER");
        node.put("size", e != null ? e.getSize() : 0);
        // T01810 FR-006 展示层：AST 口径「格式或位置变更」徽标——仅在 true 时下发，
        // 避免全量树每节点多一个 false 字段（大包万级节点的序列化体积）
        if (r != null && r.isFormattedOnly(k)) node.put("formattedOnly", true);
        // R2 二进制摘要卡（二期 T01454）：双端大小 + 双端 SHA-256。
        // SHA-256 在 parse 期已随 LogicalEntry 计算，此处仅透传，零额外 IO。
        if (oe != null) node.put("oldSize", oe.getSize());
        if (ne != null) node.put("newSize", ne.getSize());
        if (oe != null && oe.getSha256() != null) node.put("oldSha256", oe.getSha256());
        if (ne != null && ne.getSha256() != null) node.put("newSha256", ne.getSha256());
        return node;
    }

    // ---------------- Job：状态 / 报告 / 导出 ----------------

    /** 汇总「进行中」比对任务（QUEUED/RUNNING），供桌面壳在关闭窗口前查询：
     *  避免用户误关直接中断长比对/解包任务。返回 [{type,id,label,phase,message}]；
     *  无进行中任务时返回空数组。AI 分析与导出是同步 HTTP 请求内完成，随进程关闭自动中断，
     *  不产生独立 running 状态，故仅汇总比对任务本身。 */
    private void handleRunningTasks(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) { sendJson(ex, 204, new LinkedHashMap<>()); return; }
        if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "仅支持 GET"); return; }
        List<Map<String, Object>> running = new ArrayList<>();
        for (Job j : store.all().values()) {
            String st = j.getStatus();
            if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "compare");
            item.put("id", j.id);
            item.put("label", jobLabel(j));
            item.put(KEY_PHASE, j.getPhase());
            item.put(KEY_MESSAGE, j.getMessage() == null ? "" : j.getMessage());
            running.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", running);
        sendJson(ex, 200, resp);
    }

    /** 生成任务的可读标题（供弹窗列出）。Job 不落左右文件路径，用 id + 模式表达即可。 */
    private String jobLabel(Job j) {
        String mode = (j.mode == null || j.mode.isEmpty()) ? "compare" : j.mode;
        return "比对任务 " + j.id + "（" + mode + "）";
    }

    private void handleJob(HttpExchange ex) throws IOException {
        // 请求路径：/api/job/ 后接 jobId 与动作（如 status / cancel / report / export）。
        String path = ex.getRequestURI().getPath();
        String sub = path.replaceFirst("^/api/job/?", "");
        String[] parts = sub.split("/");
        if (parts.length < 1 || parts[0].isEmpty()) {
            sendError(ex, 400, "缺少 jobId");
            return;
        }
        String jobId = parts[0];
        String action = parts.length >= 2 ? parts[1] : KEY_STATUS;
        Job job = store.get(jobId);
        if (job == null) {
            sendError(ex, 404, MSG_TASK_NOT_FOUND + jobId);
            return;
        }
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        if (KEY_STATUS.equals(action)) {
            sendJobStatus(ex, job);
            return;
        }
        // 用 switch 替代串行 if 链分派其余动作，降低认知复杂度；各 case 内部仅做调用/返回，不含嵌套分支。
        switch (action) {
            case "cancel" -> {
                job.requestCancel(); // 置取消标记；后台任务在阶段边界检查并标记 CANCELLED
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put(KEY_JOB_ID, job.id);
                resp.put(KEY_STATUS, job.getStatus());
                resp.put("cancelled", job.isCancelRequested());
                sendJson(ex, 200, resp);
            }
            case "report" -> handleReport(ex, job);
            case "unpack-report" -> handleUnpackReport(ex, job);
            case "export" -> {
                // 大包异步导出入口：export/start 需第3段；否则走旧版同步导出端点（兼容旧调用）
                if (parts.length >= 3 && "start".equals(parts[2])) handleExportStart(ex, job);
                else handleExport(ex, job);
            }
            case "ai-analyze" -> handleAiAnalyze(ex, job);
            case "ai-classify" -> handleClassify(ex, job);
            case "ai-estimate" -> handleAiEstimate(ex, job);
            default -> sendError(ex, 404, "未知 job 操作: " + action);
        }
    }

    /** job 状态响应（从 handleJob 抽出，消除其内嵌「DONE」分支以降低认知复杂度）。 */
    private void sendJobStatus(HttpExchange ex, Job job) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put(KEY_JOB_ID, job.id);
        resp.put("mode", job.mode);
        resp.put(KEY_STATUS, job.getStatus());
        resp.put(KEY_PHASE, job.getPhase());
        resp.put("progress", job.getProgress());
        resp.put(KEY_MESSAGE, job.getMessage());
        resp.put(KEY_ERROR, job.getError());
        // 仅完成时回带完整产物；进行中只返回进度，避免前端在结果未就绪时读到 null 快照。
        if ("DONE".equals(job.getStatus())) {
            resp.put("oldFile", job.getOldSnap().getFile().getFileName().toString());
            resp.put("newFile", job.getNewSnap().getFile().getFileName().toString());
            resp.put("oldVersion", nullToNA(job.getOldSnap().getVersion()));
            resp.put("newVersion", nullToNA(job.getNewSnap().getVersion()));
            resp.put("stats", statsJson(job.getStats()));
            resp.put("tree", buildTree(job.getResult(), job.getOldSnap(), job.getNewSnap(), job.opts.isUnpackNested()));
        }
        sendJson(ex, 200, resp);
    }

    private void handleReport(HttpExchange ex, Job job) throws IOException {
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        try {
            boolean ai = false;
            String projDir = null;
            String category = null;   // 分析项（risk/breaking/impact/testpoints/custom）——修复：此前缺失，
            String prompt = null;     // 导致「生成报告(AI)」永远走默认分析，不同分析项报告内容相同
            if (ex.getRequestMethod().equals("POST")) {
                String body = readBody(ex);
                if (!body.isEmpty()) {
                    Map<String, Object> req = Json.parseObject(body);
                    ai = Json.bool(req, "ai", false);
                    projDir = Json.str(req, KEY_PROJECT_DIR, null);
                    category = Json.str(req, KEY_CATEGORY, null);
                    prompt = Json.str(req, KEY_PROMPT, null);
                }
            }
            // 前端未显式传 projectDir 时，回退到配置中心的项目上下文设置（旧前端/漏传均生效）
            if (projDir == null && config.isProjectContextEnabled()) projDir = config.getProjectContextDir();
            // 复用 per-job 工作缓存（反编译/文本差异/jar 分析），与 ai-analyze/export 共享，避免重复计算
            AiWorkCache work = getAiWork(job);
            MarkdownReport rep = new MarkdownReport(job.opts.getTopK());
            // T01810 FR-006 展示层：AST 折叠/降级计数进报告统计段（AI 分支由 runAiAnalysis 内同口径接线）
            rep.setFormattedOnlyCount(job.getResult().formattedOnlyCount());
            rep.setAstDegradedCount(AstNormalizer.degradedCount());
            String md = buildMarkdown(job, ai, projDir, category, prompt,
                    work.decompiled, work.text, work.libJar, rep);
            sendText(ex, 200, "text/markdown", md);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "报告生成失败", e);
            sendError(ex, 500, "报告生成失败: " + e.getMessage());
        }
    }

    private String buildMarkdown(Job job, boolean ai, String projDir, String category, String prompt, // NOSONAR - 参数为报告管线固定入参组，语义分组，拆分会降低可读性
                                 Map<String, DecompiledUnit> decompiled, Map<String, DecompiledUnit> text,
                                 LibJarDiff.Result libJar, MarkdownReport rep) {
        if (!ai) {
            return rep.render(job.getOldSnap(), job.getNewSnap(), job.getResult(), job.getStats(),
                    decompiled, text, libJar);
        }
        // AI 分支复用 runAiAnalysis，保证「报告」与「流式分析」产出完全一致（同一管线）。
        // category/prompt 透传：buildFocus 据此追加「本次分析聚焦」，使不同分析项的报告内容可区分。
        return runAiAnalysis(job, decompiled, text, libJar, projDir, category, prompt).markdown;
    }

    /**
     * 运行 AI 两阶段分析并渲染 Markdown；同时派生可视化「思考过程」步骤序列。
     * 供 report --ai 与 SSE 流式分析共用，确保两种呈现方式的内容一致。
     */
    private AiAnalysisResult runAiAnalysis(Job job, Map<String, DecompiledUnit> decompiled,
                                           Map<String, DecompiledUnit> text, LibJarDiff.Result libJar,
                                           String projDir, String category, String prompt) {
        return runAiAnalysis(job, decompiled, text, libJar, projDir, category, prompt, null, null);
    }

    /**
     * 运行 AI 两阶段分析并渲染 Markdown；同时派生可视化「思考过程」步骤序列。
     * 供 report --ai 与 SSE 流式分析共用，确保两种呈现方式的内容一致。
     * fileKey 非空时走「单文件聚焦」管线（差异树右键「AI功能总结」）：仅分析该文件，
     * 避免沿用全量管线导致结论与整体风险分析雷同、且不聚焦用户所选文件。
     * fileStatus：前端树节点透传的变更类型（归档内部复合键不在顶层 DiffResult 中，
     * 后端 statusOf 只认顶层 key 会一律误判 MODIFIED，故需前端权威值，评审 H1）。
     */
    private AiAnalysisResult runAiAnalysis(Job job, Map<String, DecompiledUnit> decompiled, // NOSONAR - 参数为 AI 管线的语义分组入参，拆分为对象降低可读性
                                           Map<String, DecompiledUnit> text, LibJarDiff.Result libJar,
                                           String projDir, String category, String prompt,
                                           String fileKey, String fileStatus) {
        if (fileKey != null && !fileKey.isEmpty()) {
            return runSingleFileAnalysis(job, decompiled, text, projDir, fileKey, fileStatus, prompt);
        }
        AiConfig aiCfg = config.toAiConfig();
        // 项目级上下文（递归识别多项目 + 缓存）：stageA 注入仓库级上下文视图；
        // stageB 逐文件按所属项目精准注入（ProjectIndex.locate）。
        ProjectIndex pIndex = ProjectContextService.resolve(projDir);
        ProjectContext ctx = ProjectContextService.renderContextView(pIndex);
        // 真实调用判定：AI 开关开启 且 已配置 API Key → HttpAiAnalyzer；否则离线回放（Mock）。
        // （原实现只看 Key，若 persistApiKey=false 重启后 Key 丢失会静默降级 Mock，用户难以察觉原因。）
        boolean realLlm = aiCfg.isEnabled() && aiCfg.getApiKey() != null && !aiCfg.getApiKey().isEmpty();
        AiAnalyzer analyzer = realLlm
                ? new HttpAiAnalyzer(aiCfg) : new MockAiAnalyzer(
                Paths.get(System.getProperty(PROP_USER_HOME), DIR_BEMPDIFF, DIR_AI_REPLAY));
        Map<String, DecompiledUnit> aiMap = new LinkedHashMap<>(decompiled);
        aiMap.putAll(text);
        List<AiAnalyzer.DecompileReq> bCands = new ArrayList<>();
        // 阶段B 深读候选：文本类用全量（buildTextMapAll，不受 topK 截断），class 用已缓存反编译 map。
        // 深度由 stageBTopK 独立上限控制；与报告正文源码章节的 topK 解耦，避免「测试要点」等专项只覆盖前几个文件导致报告不全。
        Map<String, DecompiledUnit> stageBMap = new LinkedHashMap<>(decompiled);
        stageBMap.putAll(buildTextMapAll(job));
        for (Map.Entry<String, DecompiledUnit> en : stageBMap.entrySet()) {
            ProjectContext perCtx = (pIndex != null) ? pIndex.locate(en.getKey()) : null;
            bCands.add(new AiAnalyzer.DecompileReq(en.getKey(), en.getValue(),
                    fileClassOfKey(en.getKey(), job.getOldSnap(), job.getNewSnap()), perCtx));
        }
        if (bCands.size() > aiCfg.getStageBTopK()) bCands = bCands.subList(0, aiCfg.getStageBTopK());
        String focus = buildFocus(category, prompt);
        // 方案B：以用户所选分析项为权威类别（唯一事实源）。prompt 的分类结论 schema 注入与渲染分支
        // 均据此类别决定，杜绝「从聚焦文本反推」导致的类别错位；据此离线（Mock/回放）与真实 HTTP 行为一致。
        String normCat = normalizeCategory(category, prompt);
        StageASummary summary = analyzer.stageA(job.getResult(), aiMap, aiCfg, ctx, focus, normCat, job.getStats());
        if (normCat != null) summary.setCategory(normCat);
        List<FileAnalysis> b = analyzer.stageB(bCands, aiCfg, ctx, focus);
        MarkdownReport rep = new MarkdownReport(job.opts.getTopK());
        // 报告 AI 章节标注本次分析聚焦项，让「所选分析项」在报告内容中显式可见
        rep.setAiFocus(categoryLabel(category, prompt));
        // T01810 FR-006 展示层：AST 折叠/降级计数——AI 与非 AI 两条报告路径同口径
        rep.setFormattedOnlyCount(job.getResult().formattedOnlyCount());
        rep.setAstDegradedCount(AstNormalizer.degradedCount());
        // 聚焦精简报告：仅当用户显式选择具体专项（权威类别为破坏性/影响/测试要点/自定义）时启用——
        // 以 AI 章节为核心，其余章节压缩为背景（差异规模、变更文件清单），标题随分析主题（如「测试要点分析报告」）；
        // 整体风险分析（risk）与默认整体分析（normCat=null）仍走完整差异报告，
        // 保留差异统计/文件树/源码文本差异/破坏性清单/审计摘要，避免把整体风险结论裁成孤立的摘要页。
        boolean overallRisk = normCat == null || "risk".equals(normCat);
        String md;
        if (!overallRisk) {
            rep.setAiSectionTitle("## AI 智能分析（两阶段 / 项目级上下文增强）");
            md = rep.renderFocusReport(job.getOldSnap(), job.getNewSnap(), job.getResult(), job.getStats(),
                    summary, b, ctx);
        } else {
            md = rep.render(job.getOldSnap(), job.getNewSnap(), job.getResult(), job.getStats(),
                    decompiled, text, summary, b, ctx, libJar);
        }
        List<Map<String, Object>> thinking = buildThinkingSteps(job, summary, b, ctx);
        // FR-013：实际消耗（输入/输出分段）进思考步骤（前端 AiConsole 自动渲染）与报告审计摘要；
        // Mock/离线链路无 usage → usageCalls=0 不追加，行为与旧版一致。
        if (analyzer instanceof HttpAiAnalyzer http && http.getUsageCalls() > 0) {
            addStep(thinking, STAGE_THINKING, "AI 实际消耗：输入 " + http.getUsagePromptTokens()
                    + " / 输出 " + http.getUsageCompletionTokens()
                    + " tokens（" + http.getUsageCalls() + " 次调用）");
            rep.setAiUsageLine("输入 " + http.getUsagePromptTokens() + " / 输出 "
                    + http.getUsageCompletionTokens() + " tokens（" + http.getUsageCalls() + " 次调用）");
            // 消耗行渲染进审计摘要：整体分析报告在此重渲染 footer；聚焦报告路径无审计摘要则跳过
            if (overallRisk) {
                md = rep.render(job.getOldSnap(), job.getNewSnap(), job.getResult(), job.getStats(),
                        decompiled, text, summary, b, ctx, libJar);
            }
        }
        return new AiAnalysisResult(md, thinking);
    }

    /**
     * 单文件聚焦分析（差异树右键「AI功能总结」）：只对指定文件计算差异并送 AI 深读，
     * 跳过 stageA 整体概览，也不用全量报告模板——保证结论围绕该文件功能展开。
     *
     * <p>关键点：定位差异单元时优先复用工作缓存，未命中则现场计算该单个文件——
     * 全量管线按 topK 截断候选，右键的文件很可能根本不在 AI 上下文里，
     * 模型拿不到该文件的实际 diff，只能复述整体统计（用户反馈的病灶）。</p>
     *
     * <p>fileStatus：前端树节点透传的权威变更类型。归档内部条目（复合键）不在顶层
     * DiffResult 里，statusOf 对其必然回落 MODIFIED（评审 H1）；非法/缺省值仍回退 statusOf。</p>
     */
    private AiAnalysisResult runSingleFileAnalysis(Job job, Map<String, DecompiledUnit> decompiled,
                                                   Map<String, DecompiledUnit> text, String projDir,
                                                   String fileKey, String fileStatus, String prompt) {
        // 分类需区分顶层/复合键（归档内部条目按内部路径扩展名），与 handleEntry 口径一致
        FileClass fc = fileClassOfTreeKey(job, fileKey);
        if (fc == FileClass.FOLDER) {
            throw new IllegalArgumentException("文件夹无内容差异，请选择具体文件进行 AI 功能总结");
        }
        DecompiledUnit unit = text.get(fileKey);
        if (unit == null) unit = decompiled.get(fileKey);
        if (unit == null) {
            // 缓存未命中：单文件现场计算（支持复合键/Office/归档清单等全类型分派），
            // 天然不受 topK 截断影响——全量管线按 topK 截断候选，右键的文件很可能
            // 根本不在 AI 上下文里，模型拿不到该文件实际 diff 只能复述整体统计（用户反馈病灶）
            try {
                unit = computeUnitByKey(job, fileKey);
            } catch (IOException e) {
                throw new IllegalArgumentException("读取文件差异失败: " + e.getMessage());
            }
        }
        if (!unit.isOk()) {
            throw new IllegalArgumentException(
                    "文件 " + fileKey + " 无法提取内容差异（" + unit.getError() + "），暂不支持 AI 功能总结");
        }
        AiConfig aiCfg = config.toAiConfig();
        ProjectIndex pIndex = ProjectContextService.resolve(projDir);
        ProjectContext ctx = ProjectContextService.renderContextView(pIndex);
        boolean realLlm = aiCfg.isEnabled() && aiCfg.getApiKey() != null && !aiCfg.getApiKey().isEmpty();
        AiAnalyzer analyzer = realLlm
                ? new HttpAiAnalyzer(aiCfg) : new MockAiAnalyzer(
                Paths.get(System.getProperty(PROP_USER_HOME), DIR_BEMPDIFF, DIR_AI_REPLAY));
        // 仅该文件进入 stageB；聚焦指令强调功能维度，杜绝模型展开其他文件
        ProjectContext perCtx = (pIndex != null) ? pIndex.locate(fileKey) : null;
        List<AiAnalyzer.DecompileReq> cands = new ArrayList<>();
        cands.add(new AiAnalyzer.DecompileReq(fileKey, unit, fc, perCtx));
        List<FileAnalysis> b = analyzer.stageB(cands, aiCfg, ctx, singleFileFocus(prompt));
        FileAnalysis fa = b.isEmpty() ? null : b.get(0);
        DiffStatus st = parseStatus(fileStatus);
        if (st == null) st = statusOf(job.getResult(), fileKey);
        // topK 仅为 MarkdownReport 构造器签名所需（其内部 diff 渲染上限），renderSingleFile 不消费（评审 L5）
        String md = new MarkdownReport(job.opts.getTopK())
                .renderSingleFile(fileKey, st, fc, unit, fa, ctx);
        List<Map<String, Object>> thinking = new ArrayList<>();
        addStep(thinking, STAGE_THINKING, "单文件聚焦分析：" + fileKey + "（变更类型 " + st + "）");
        if (fa != null) {
            addStep(thinking, STAGE_THINKING, "分析文件 " + fileKey + "：意图=" + nz(fa.getIntent())
                    + "；风险=" + nz(fa.getRisk()) + "；影响=" + nz(fa.getImpact()));
        }
        // FR-013：单文件分析的实际消耗（真实 LLM 才有 usage，Mock 链路 usageCalls=0 跳过）
        if (analyzer instanceof HttpAiAnalyzer http && http.getUsageCalls() > 0) {
            addStep(thinking, STAGE_THINKING, "AI 实际消耗：输入 " + http.getUsagePromptTokens()
                    + " / 输出 " + http.getUsageCompletionTokens()
                    + " tokens（" + http.getUsageCalls() + " 次调用）");
        }
        return new AiAnalysisResult(md, thinking);
    }

    /** 单文件聚焦指令 + 可选用户补充问题：API 直调带 prompt 时不再被静默丢弃（评审 L3）。 */
    private static String singleFileFocus(String prompt) {
        if (prompt != null && !prompt.trim().isEmpty()) {
            return SINGLE_FILE_FOCUS + "\n用户补充问题：" + prompt.trim();
        }
        return SINGLE_FILE_FOCUS;
    }

    /** 解析前端透传的变更类型名（DiffStatus 枚举名）；非法/缺省返回 null，调用方回退 statusOf。 */
    private static DiffStatus parseStatus(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return DiffStatus.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 用户是否提供了自定义分析 prompt（trim 后非空）——三处「prompt 优先」判定抽出的公共谓词。 */
    private static boolean hasUserPrompt(String prompt) {
        return prompt != null && !prompt.trim().isEmpty();
    }

    /** 分析项可读标签：用于报告「分析聚焦」标注。custom 或未知类别返回自定义问题/默认整体分析。 */
    private static String categoryLabel(String category, String prompt) {
        if (hasUserPrompt(prompt)) {
            return "自定义问题：" + prompt.trim();
        }
        if (category == null || category.isEmpty()) return CAT_OVERALL;
        switch (category) {
            case "risk": return CAT_OVERALL;
            case CAT_BREAKING: return "破坏性变更专项";
            case CAT_IMPACT: return "影响范围分析";
            case CAT_TESTPOINTS: return "测试要点分析";
            case CAT_CUSTOM: return "自定义问题";
            default: return CAT_OVERALL;
        }
    }

    /** 由类别/自定义 prompt 构造聚焦指令（非空时引导模型在对应维度深入，使不同类别分析报告内容可区分）。 */
    private static String buildFocus(String category, String prompt) {
        if (hasUserPrompt(prompt)) {
            return "请围绕以下用户问题进行分析：" + prompt.trim();
        }
        if (category == null || category.isEmpty()) return null;
        switch (category) {
            case "risk": return "本次分析请重点聚焦【整体风险等级与降级/回滚预案】，给出明确的风险结论与应对建议。";
            case CAT_BREAKING: return "本次分析请重点聚焦【破坏性变更与向后兼容性】，逐一指出删除/签名变更/接口契约破坏等不兼容点。";
            case CAT_IMPACT: return "本次分析请重点聚焦【影响范围与上下游模块依赖】，说明本次变更会波及哪些对外接口与内部调用方。";
            case CAT_TESTPOINTS: return "本次分析请重点聚焦【回归测试要点】，给出可执行的测试场景、用例思路与验证重点。";
            default: return null;
        }
    }

    /** 规范化分析类别枚举：自定义 prompt 优先归为 custom；非法/缺省返回 null（渲染端回落为整体全面）。 */
    private static String normalizeCategory(String category, String prompt) {
        if (hasUserPrompt(prompt)) return CAT_CUSTOM;
        if (category == null) return null;
        switch (category) {
            case "risk": case CAT_BREAKING: case CAT_IMPACT: case CAT_TESTPOINTS: case CAT_CUSTOM:
                return category;
            default: return null;
        }
    }

    /** 从阶段A/阶段B 结果派生「思考过程」步骤（前端折叠、浅灰展示）。 */
    private List<Map<String, Object>> buildThinkingSteps(Job job, StageASummary summary,
                                                         List<FileAnalysis> b, ProjectContext ctx) {
        List<Map<String, Object>> steps = new ArrayList<>();
        DiffStats s = job.getStats();
        addStep(steps, STAGE_THINKING,
                "综合差异统计：新增 " + s.getAdded() + " 处、修改 " + s.getModified() + " 处、删除 "
                        + s.getDeleted() + " 处；判断整体风险等级为「" + nz(summary.getOverallRisk())
                        + "」，影响范围：" + nz(summary.getImpactScope()));
        if (summary.getTestThemes() != null) {
            for (String t : summary.getTestThemes()) {
                addStep(steps, STAGE_THINKING, "提取测试要点：" + nz(t));
            }
        }
        if (b != null) {
            buildFileThinkingSteps(steps, b);
        }
        if (ctx != null && ctx.getSummary() != null && !ctx.getSummary().isEmpty()) {
            addStep(steps, STAGE_THINKING, "结合项目上下文画像：" + nz(ctx.getSummary()));
        }
        return steps;
    }

    /** 逐文件聚合「分析文件…」思考步骤 (提取自 buildThinkingSteps 以降低认知复杂度). */
    private static void buildFileThinkingSteps(List<Map<String, Object>> steps, List<FileAnalysis> b) {
        for (FileAnalysis fa : b) {
            StringBuilder sb = new StringBuilder();
            sb.append("分析文件 ").append(nz(fa.getKey())).append("：意图=").append(nz(fa.getIntent()))
                    .append("；风险=").append(nz(fa.getRisk()));
            if (fa.getImpact() != null && !fa.getImpact().isEmpty()) {
                sb.append("；影响=").append(fa.getImpact());
            }
            if (fa.getContextInfluence() != null && !fa.getContextInfluence().isEmpty()) {
                sb.append("；上下文影响=").append(fa.getContextInfluence());
            }
            addStep(steps, STAGE_THINKING, sb.toString());
        }
    }

    private static void addStep(List<Map<String, Object>> steps, String phase, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_PHASE, phase);
        m.put(KEY_MESSAGE, message);
        steps.add(m);
    }

    private static String nz(String s) {
        return (s == null || s.isEmpty()) ? "未提供" : s;
    }

    /** AI 分析结果：渲染后的 Markdown 全文 + 可视化思考步骤。 */
    private static final class AiAnalysisResult {
        final String markdown;
        final List<Map<String, Object>> thinking;
        AiAnalysisResult(String markdown, List<Map<String, Object>> thinking) {
            this.markdown = markdown;
            this.thinking = thinking;
        }
    }

    /** per-job 的 AI 工作负载（不可变快照）：反编译类图 + 前端文本差异 + 依赖 jar 分析，供多入口复用。 */
    private static final class AiWorkCache {
        final Map<String, DecompiledUnit> decompiled;
        final Map<String, DecompiledUnit> text;
        final LibJarDiff.Result libJar;
        AiWorkCache(Map<String, DecompiledUnit> decompiled, Map<String, DecompiledUnit> text,
                    LibJarDiff.Result libJar) {
            this.decompiled = decompiled;
            this.text = text;
            this.libJar = libJar;
        }
    }

    /** 取/算某 job 的 AI 工作负载：首次计算后按 jobId 缓存，report/ai-analyze/export 复用（评审 P1 #8）。 */
    private AiWorkCache getAiWork(Job job) {
        AiWorkCache c = aiWorkCache.get(job.id);
        if (c == null) {
            Map<String, DecompiledUnit> decompiled = buildClassMap(job, job.opts.getTopK());
            Map<String, DecompiledUnit> text = buildTextMap(job, job.opts.getTopK());
            LibJarDiff.Result libJar = LibJarDiff.analyze(job.getOldSnap(), job.getNewSnap(), job.getResult(),
                    new Decompiler(cfrPath(job.opts), findJava()), job.opts.getTopK());
            c = new AiWorkCache(decompiled, text, libJar);
            aiWorkCache.put(job.id, c); // T00427：LRU 容量到顶自动逐出最旧，无需全清
        }
        return c;
    }

    private void handleExport(HttpExchange ex, Job job) throws IOException {
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        Path outDir = Files.createTempDirectory(EXPORT_TEMP_PREFIX); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
        try {
            // P1-2：同 job 已导出过则直接复用缓存 zip，跳过重复 exportIncrement+zipTree（增量 zip 由不可变 diff/snap 幂等派生）。
            Path cached = cachedExportZip(job.id, "all");
            if (cached != null) {
                Path finalZip = materializeCachedZip(cached, outDir);
                sendFile(ex, 200, MIME_ZIP, finalZip);
                return;
            }
            AssetExporter exporter = new AssetExporter();
            // 增量更新资产：increment/(新包 ADDED+MODIFIED 完整文件，保持路径) + deleted/(老包被删文件)。
            // 不导出反编译源码/差异片段——用户解压 increment/ 覆盖即可完成增量部署。
            exporter.exportIncrement(job.getResult(), job.getOldSnap(), job.getNewSnap(), outDir);
            // 所有产物（increment/、deleted/）统一打包为最终下载 zip
            Path finalZip = exporter.zipTree(outDir, outDir.resolve(EXPORT_ZIP_NAME));
            cacheExportZip(job.id, "all", finalZip); // 本 job 增量资产不可变，落盘缓存供二次导出复用
            // 流式下发 zip：避免整包读入内存（readAll）导致超大响应体传输中断/断连（前端报 Failed to fetch）
            sendFile(ex, 200, MIME_ZIP, finalZip);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "资产导出失败", e);
            sendError(ex, 500, "资产导出失败: " + e.getMessage());
        } finally {
            // 清理临时目录树：多次导出也不在 %TEMP% 累积差异资产（increment/deleted/zip）。
            // sendFile 为同步写完才返回，此处删除不会截断已在传输的响应体。
            deleteTree(outDir);
        }
    }

    // ---------------- 差异资产导出（同步/异步 + 下载管理） ----------------

    /** 同步阈值：小于此规模走同步流式（实时反馈进度）；超过则异步生成避免阻塞。 */
    private static final long SMALL_EXPORT_BYTES = 256L * 1024 * 1024;
    private static final long SMALL_EXPORT_FILES = 20000L;
    private static final long ETA_BYTES_PER_SEC = 40L * 1024 * 1024; // 打包吞吐估算（保守），用于预计耗时

    /** 处理 /api/export 下的管理端点：list / get / download / delete。 */
    private void handleExportApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        String path = ex.getRequestURI().getPath();
        String sub = path.replaceFirst("^/api/export/?", "");
        if (sub.isEmpty() || sub.equals("list")) {
            handleExportList(ex);
            return;
        }
        String[] parts = sub.split("/");
        ExportRecord r = exports.get(parts[0]);
        if (r == null) { sendError(ex, 404, "导出记录不存在: " + parts[0]); return; }
        if (parts.length == 1) { sendJson(ex, 200, exportToJson(r)); return; }
        switch (parts[1]) {
            case "download":
                if (!"done".equals(r.status)) { sendError(ex, 409, "导出未完成或失败，无法下载"); return; }
                Path file = exportsDir().resolve(r.id).resolve(r.filename);
                if (!Files.isRegularFile(file)) { sendError(ex, 404, "导出文件不存在"); return; }
                sendFile(ex, 200, MIME_ZIP, file);
                return;
            case "delete":
                if (!ex.getRequestMethod().equals("POST") && !ex.getRequestMethod().equals("DELETE")) {
                    sendError(ex, 405, "仅支持 POST/DELETE");
                    return;
                }
                deleteTree(exportsDir().resolve(r.id));
                exports.remove(parts[0]);
                sendJson(ex, 200, java.util.Map.of("ok", true));
                return;
            default:
                sendError(ex, 404, "未知操作: " + parts[1]);
        }
    }

    /** 导出记录列表（从 handleExportApi 抽出以降低其认知复杂度）。 */
    private void handleExportList(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "仅支持 GET"); return; }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExportRecord r : exports.values()) list.add(exportToJson(r));
        list.sort((a, b) -> Long.compare((Long) b.get(KEY_CREATED_AT), (Long) a.get(KEY_CREATED_AT)));
        sendJson(ex, 200, list);
    }

    /** 导出规模估算（纯函数，供单测）：{files, bytes, small, etaSeconds, etaText}。 */
    public static Map<String, Object> exportEstimate(DiffResult r, Map<String, LogicalEntry> oldSnap, Map<String, LogicalEntry> nowSnap) {
        long bytes = 0;
        long files = 0;
        for (String k : r.get(DiffStatus.ADDED)) { LogicalEntry e = nowSnap.get(k); if (e != null) { files++; bytes += e.getSize(); } }
        for (String k : r.get(DiffStatus.MODIFIED)) { LogicalEntry e = nowSnap.get(k); if (e != null) { files++; bytes += e.getSize(); } }
        for (String k : r.get(DiffStatus.DELETED)) { LogicalEntry e = oldSnap.get(k); if (e != null) { files++; bytes += e.getSize(); } }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_FILES, files);
        m.put("bytes", bytes);
        m.put("small", isLargeExport(files, bytes) ? Boolean.FALSE : Boolean.TRUE);
        long secs = Math.max(1, bytes / ETA_BYTES_PER_SEC);
        m.put("etaSeconds", secs);
        m.put("etaText", formatEta(secs));
        return m;
    }

    /** 大包判定（纯函数，供单测）。 */
    public static boolean isLargeExport(long files, long bytes) {
        return files > SMALL_EXPORT_FILES || bytes > SMALL_EXPORT_BYTES;
    }

    private static Path exportsDir() {
        return Paths.get(System.getProperty(PROP_USER_HOME, ""), DIR_BEMPDIFF, "exports");
    }

    /** P1-2 增量导出缓存目录：{user.home}/.bempdiff/exports/_export_cache/。
     *  独立于 exports 下载目录，避免与「大包异步导出记录」的文件布局混淆。 */
    private static Path exportCacheDir() {
        return exportsDir().resolve(EXPORT_CACHE_SUBDIR);
    }

    /** 取某 job + 粒度 的缓存 zip 路径；未生成过则返回 null。
     *  粒度（all/jar/class）纳入缓存键，避免不同粒度的导出结果互相复用（T02742）。 */
    private Path cachedExportZip(String jobId, String granularity) {
        Path p = exportZipCache.get(cacheKey(jobId, granularity));
        return (p != null && Files.isRegularFile(p)) ? p : null;
    }

    /** 生成后回填缓存：把新 zip 拷入缓存目录并按 jobId+粒度 记录，供同 job 同粒度二次导出复用（T02742）。 */
    private Path cacheExportZip(String jobId, String granularity, Path srcZip) throws IOException {
        Files.createDirectories(exportCacheDir());
        Path dst = exportCacheDir().resolve(EXPORT_CACHE_FILE_PREFIX + cacheKey(jobId, granularity) + ".zip");
        Files.copy(srcZip, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        exportZipCache.put(cacheKey(jobId, granularity), dst);
        return dst;
    }

    /** 导出缓存复合键：jobId + 粒度，保证不同粒度（all/jar/class）缓存彼此独立（T02742）。 */
    private static String cacheKey(String jobId, String granularity) {
        return jobId + "_" + (granularity == null ? "all" : granularity);
    }

    /** P1-2：从缓存 zip 生成一个可发送的副本到 outDir（保留原临时目录清理语义，不污染缓存）。 */
    private Path materializeCachedZip(Path cachedZip, Path outDir) throws IOException {
        Path copy = outDir.resolve(EXPORT_ZIP_NAME);
        Files.copy(cachedZip, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    private static String formatEta(long secs) {
        return secs < 60 ? "约" + secs + " 秒" : "约" + Math.max(1, (secs + 59) / 60) + " 分钟";
    }

    private static Map<String, Object> exportToJson(ExportRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put(KEY_JOB_ID, r.jobId);
        m.put("filename", r.filename);
        m.put(KEY_STATUS, r.status);
        m.put("estimatedBytes", r.estimatedBytes);
        m.put("size", r.size);
        m.put(KEY_CREATED_AT, r.createdAt);
        m.put("finishedAt", r.finishedAt);
        m.put(KEY_MESSAGE, r.message);
        m.put("incrementCount", r.incrementCount);
        m.put("deletedCount", r.deletedCount);
        m.put("etaText", formatEta(Math.max(1, r.estimatedBytes / ETA_BYTES_PER_SEC)));
        return m;
    }

    /** 按粒度导出差异资产到 outDir（T02742）：
     *  - all   ：增量全量（increment/ + deleted/，可覆盖部署）
     *  - jar   ：仅变化的 lib/*.jar（diff-jars/）
     *  - class ：仅变化的 L1 class 字节（diff-classes/）
     *  三者均只导出「有差异」的资产（ADDED/DELETED/MODIFIED），未变化文件不进入产物。 */
    private void exportByGranularity(AssetExporter exporter, String granularity, DiffResult r,
                                     PackageSnapshot oldSnap, PackageSnapshot newSnap, Path outDir) throws IOException {
        if ("jar".equals(granularity)) {
            exporter.exportDiffJars(r, oldSnap, newSnap, outDir);
        } else if ("class".equals(granularity)) {
            exporter.exportDiffClasses(r, oldSnap, newSnap, outDir);
        } else {
            exporter.exportIncrement(r, oldSnap, newSnap, outDir);
        }
    }

    /** 导出入口：小包同步流式（200 zip，前端读流显示进度）；大包异步生成（202 JSON 任务 id，前端轮询）。 */
    private void handleExportStart(HttpExchange ex, Job job) throws IOException {
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) { sendError(ex, 405, "仅支持 POST"); return; }
        Map<String, Object> reqBody = Json.parseObject(readBody(ex));
        // T02742：导出粒度（all=增量全量 / jar=变化 jar / class=变化 class）；缺省回退 all 保持向后兼容。
        String granularity = Json.str(reqBody, "granularity", "all");
        DiffResult r = job.getResult();
        Map<String, Object> est = exportEstimate(r, job.getOldSnap().getEntries(), job.getNewSnap().getEntries());
        long files = ((Number) est.get(KEY_FILES)).longValue();
        long bytes = ((Number) est.get("bytes")).longValue();
        if (!isLargeExport(files, bytes)) {
            // 小包：同步流式导出。发送完成后才返回，删除临时树不会截断响应体。
            Path outDir = Files.createTempDirectory(EXPORT_TEMP_PREFIX); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
            try {
                // P1-2：同 job 同粒度已导出过则复用缓存 zip，跳过重复导出+打包（差异资产按 job+粒度 幂等派生）。
                Path cached = cachedExportZip(job.id, granularity);
                if (cached != null) {
                    sendFile(ex, 200, MIME_ZIP, materializeCachedZip(cached, outDir));
                } else {
                    AssetExporter exporter = new AssetExporter();
                    exportByGranularity(exporter, granularity, r, job.getOldSnap(), job.getNewSnap(), outDir);
                    Path zip = exporter.zipTree(outDir, outDir.resolve(EXPORT_ZIP_NAME));
                    cacheExportZip(job.id, granularity, zip);
                    sendFile(ex, 200, MIME_ZIP, zip);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "同步导出失败", e);
                sendError(ex, 500, "资产导出失败: " + e.getMessage());
            } finally {
                deleteTree(outDir);
            }
            return;
        }
        // 大包：异步后台生成，返回任务 id 供前端轮询/下载管理。
        String id = "exp_" + Long.toHexString(System.nanoTime()) + "_" + job.id;
        ExportRecord rec = new ExportRecord(id, job.id);
        // T02742：文件名携带粒度，便于下载管理区分 all/jar/class 三种导出产物。
        rec.filename = EXPORT_TEMP_PREFIX + job.id + "_" + granularity + ".zip";
        rec.estimatedBytes = bytes;
        exports.put(id, rec);
        exportPool.submit(() -> runAsyncExport(rec, job, granularity));
        sendJson(ex, 202, exportToJson(rec));
    }

    private void runAsyncExport(ExportRecord rec, Job job, String granularity) {
        try {
            rec.status = "running";
            Path dir = exportsDir().resolve(rec.id);
            deleteTree(dir);
            Files.createDirectories(dir);
            Path outRoot = Files.createTempDirectory(exportsDir(), "build-");
            try {
                DiffResult r = job.getResult();
                AssetExporter exporter = new AssetExporter();
                exportByGranularity(exporter, granularity, r, job.getOldSnap(), job.getNewSnap(), outRoot);
                Path zip = exporter.zipTree(outRoot, dir.resolve(rec.filename));
                rec.size = Files.size(zip);
                // T02742 计数语义：全量增量导出区分 increment/deleted；jar/class 粒度下差异资产统一归入
                // 对应目录（diff-jars/ 或 diff-classes/），故增量数按该粒度实际导出的差异条目计、删除数为 0。
                if ("all".equals(granularity)) {
                    rec.incrementCount = r.get(DiffStatus.ADDED).size() + r.get(DiffStatus.MODIFIED).size();
                    rec.deletedCount = r.get(DiffStatus.DELETED).size();
                } else {
                    rec.incrementCount = r.get(DiffStatus.ADDED).size() + r.get(DiffStatus.MODIFIED).size()
                            + r.get(DiffStatus.DELETED).size();
                    rec.deletedCount = 0;
                }
                rec.status = "done";
            } finally {
                deleteTree(outRoot);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "异步导出失败", e);
            rec.status = KEY_ERROR; // 导出失败状态串；KEY_ERROR 值即 "error"，复用既有常量避免裸字面量
            rec.message = "导出失败: " + e.getMessage();
        } finally {
            rec.finishedAt = System.currentTimeMillis();
        }
    }

    // ---------------- AI 流式分析（SSE） ----------------

    /**
     * AI 分析流式端点（SSE）。比对完成后，逐条推送「思考过程」(thinking)，再以字符块流式推送
     * Markdown 结论(answer)，最后发送 done（异常则 error）。与 report --ai 共用 runAiAnalysis 管线。
     */
    private void handleAiAnalyze(HttpExchange ex, Job job) throws IOException {
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        try {
            String projDir = null;
            String category = null;
            String prompt = null;
            String fileKey = null;
            String fileStatus = null;
            if (ex.getRequestMethod().equals("POST")) {
                String body = readBody(ex);
                if (!body.isEmpty()) {
                    Map<String, Object> req = Json.parseObject(body);
                    projDir = Json.str(req, KEY_PROJECT_DIR, null);
                    category = Json.str(req, KEY_CATEGORY, null);
                    prompt = Json.str(req, KEY_PROMPT, null);
                    // 差异树右键「AI功能总结」：仅聚焦该文件，走单文件管线而非全量分析
                    fileKey = Json.str(req, "fileKey", null);
                    // 复合键（归档内部条目）的权威变更类型由前端树节点透传（评审 H1）
                    fileStatus = Json.str(req, "fileStatus", null);
                }
            }
            // 前端未显式传 projectDir 时，回退到配置中心的项目上下文设置（旧前端/漏传均生效）
            if (projDir == null && config.isProjectContextEnabled()) projDir = config.getProjectContextDir();
            // 复用 per-job 工作缓存（反编译/文本差异/jar 分析）：并行多类别分析共享同一份，避免 N 倍重复计算
            AiWorkCache work = getAiWork(job);
            Map<String, DecompiledUnit> decompiled = work.decompiled;
            Map<String, DecompiledUnit> text = work.text;
            LibJarDiff.Result libJar = work.libJar;

            // SSE 响应头：流式、禁缓冲/缓存、同源。content-length 不设置 → 分块传输。
            ex.getResponseHeaders().set(HDR_CONTENT_TYPE, "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();

            // 即时反馈（评审 M4）：真实 LLM 调用可能长达数分钟，思考步骤均在 runAiAnalysis
            // 返回后才推送——先发一条 thinking 让控制台立刻有输出，避免「点了没反应」体感
            Map<String, Object> first = new LinkedHashMap<>();
            first.put(KEY_PHASE, STAGE_THINKING);
            first.put(KEY_MESSAGE, "正在分析 " + (fileKey != null && !fileKey.isEmpty() ? fileKey : "整体差异") + " …");
            sseEvent(os, STAGE_THINKING, first);
            os.flush();

            AiAnalysisResult result;
            try { // NOSONAR(S1141) - AI 失败在局部产出 SSE 错误，需内层 try
                result = runAiAnalysis(job, decompiled, text, libJar, projDir, category, prompt, fileKey, fileStatus);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "AI 分析失败", e);
                sseEvent(os, KEY_ERROR, Map.of(KEY_MESSAGE, "AI 分析失败: " + e.getMessage()));
                os.flush();
                os.close();
                return;
            }

            // 1) 思考过程：逐条推送（前端折叠展示，浅灰）
            for (Map<String, Object> step : result.thinking) {
                sseEvent(os, STAGE_THINKING, step);
                os.flush();
                sleepQuietly(110);
            }
            // 2) 答案：按字符块流式推送 Markdown（逐字呈现）
            String md = result.markdown;
            final int CHUNK = 28;
            for (int i = 0; i < md.length(); i += CHUNK) {
                int end = Math.min(md.length(), i + CHUNK);
                sseEvent(os, "answer", Map.of("text", md.substring(i, end)));
                os.flush();
                sleepQuietly(16);
            }
            // 3) 完成
            sseEvent(os, "done", new LinkedHashMap<>());
            os.flush();
            os.close();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "AI 流式分析异常", e);
            try {
                sseEvent(ex.getResponseBody(), KEY_ERROR, Map.of(KEY_MESSAGE, "AI 流式分析异常: " + e.getMessage()));
            } catch (IOException ignore) {
                // 响应已无法回写，忽略
            }
        }
    }

    /** 写出一个 SSE 事件（event: X\\ndata: <json>\\n\\n）。 */
    private static void sseEvent(OutputStream os, String event, Object data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append("\n");
        sb.append("data: ").append(Json.write(data)).append("\n\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 静默休眠（流式节奏控制）；被打断时恢复中断标记。 */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- 单条目反编译 ----------------

    private void handleEntry(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String sub = path.replaceFirst("^/api/entry/?", "");
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        if ("children".equals(sub)) {
            handleEntryChildren(ex);
            return;
        }
        if ("recursive".equals(sub)) {
            handleEntryRecursive(ex);
            return;
        }
        if (!"decompile".equals(sub)) {
            sendError(ex, 404, "未知 entry 操作: " + sub);
            return;
        }
        Job job = requireDoneJobWithKey(ex);
        if (job == null) return;
        String key = queryParam(ex.getRequestURI(), "key");
        try {
            DecompiledUnit u = computeUnitByKey(job, key);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("key", key);
            resp.put("engine", u.getEngine());
            resp.put("ok", u.isOk());
            resp.put(KEY_ERROR, u.getError());
            resp.put("oldSource", u.getOldSource());
            resp.put("newSource", u.getNewSource());
            resp.put("diffText", u.getDiffText());
            // 透出 git 风格 7 位短哈希，供 DiffView filebar 标题使用（缺失侧用 "0000000" 占位）
            resp.put("oldHash", u.getOldHash());
            resp.put("newHash", u.getNewHash());
            sendJson(ex, 200, resp);
        } catch (Throwable e) { // NOSONAR S1181 - 反编译链路会抛 Error（如 NoClassDefFoundError），必须捕获 Throwable 给前端 500 而非让请求挂起
            // 捕获 Throwable 而非 Exception：反编译链路若抛 Error（如 CFR 依赖缺失的
            // NoClassDefFoundError），旧代码只 catch Exception 会让请求永远无响应（客户端超时挂起）。
            // 此处统一回 500，让前端拿到明确失败而非一直转圈。
            LOG.log(Level.WARNING, e, () -> "反编译失败: " + key);
            sendError(ex, 500, "反编译失败: " + e.getMessage());
        }
    }

    /** 按差异树 key 判定文件分类：复合键（归档内部条目）按内部路径扩展名，顶层条目按条目登记分类。 */
    private static FileClass fileClassOfTreeKey(Job job, String key) {
        List<String> parts = ArchiveTree.splitCompound(key);
        if (parts.size() >= 2) return PackageParser.classify(parts.get(parts.size() - 1));
        return fileClassOfKey(parts.get(0), job.getOldSnap(), job.getNewSnap());
    }

    /**
     * 取 key 对应的新侧条目：优先精确 key；版本改名配对（MODIFIED 用旧侧 key 记录）经 DiffResult
     * 的改名映射翻译为新侧 key 再取。否则新侧取不到对应文件（如 quoteRebuyInput.14da1892.js 的新侧
     * 是 quoteRebuyInput.b03d20a7.js），内容 diff 会误判为「整文件删除」（用户实测）。
     */
    private static LogicalEntry resolveNewEntry(Job job, String key) {
        LogicalEntry ne = job.getNewSnap().getEntries().get(key);
        if (ne != null) return ne;
        String nKey = job.getResult().newKeyFor(key);
        return (nKey == null) ? null : job.getNewSnap().getEntries().get(nKey);
    }

    /**
     * 取 key 对应的旧侧条目：优先精确 key；差异树以「新侧 key」展示版本改名配对的 MODIFIED 条目后，
     * 内容层按新 key 取旧侧需经改名映射反查旧 key。否则旧侧取不到对应文件，会误判为「整文件新增」。
     */
    private static LogicalEntry resolveOldEntry(Job job, String key) {
        LogicalEntry oe = job.getOldSnap().getEntries().get(key);
        if (oe != null) return oe;
        String oKey = job.getResult().oldKeyFor(key);
        return (oKey == null) ? null : job.getOldSnap().getEntries().get(oKey);
    }

    /**
     * 按差异树 key（顶层条目或归档内部复合键 outer!/inner）计算内容级差异单元。
     * 提取自 handleEntry(decompile)：单条目反编译与单文件「AI功能总结」共用同一分派逻辑，
     * 保证右键总结拿到的差异内容与用户在 DiffView 看到的完全一致。
     */
    private DecompiledUnit computeUnitByKey(Job job, String key) throws IOException {
        List<String> parts = ArchiveTree.splitCompound(key);
        String topKey = parts.get(0);
        String innerPath = (parts.size() >= 2) ? parts.get(parts.size() - 1) : null;
        FileClass fc;
        if (innerPath != null) {
            fc = PackageParser.classify(innerPath); // 内部条目按扩展名判定
        } else {
            LogicalEntry oe = resolveOldEntry(job, topKey);
            LogicalEntry ne = resolveNewEntry(job, topKey);
            fc = entryFileClass(oe, ne);
        }
        com.bempdiff.diff.DiffRules rules = job.opts.toDiffRules();
        // 构建元数据（pom.properties / MANIFEST 等）被树状态折叠为 UNCHANGED 的文本条目：
        // 展开视图与树口径一致——先做构建噪声归一化再 diff，避免「树置灰但对比栏显示日期差异」的矛盾。
        // 仅对树判未变（被噪声归一等折叠）的条目生效；MODIFIED 的构建元数据保留原始 diff（真实差异仍展示）。
        boolean foldBuildNoise = fc != null && fc.isTextDiffable()
                && DiffEngine.isBuildMetadataKey(key)
                && job.getResult().get(DiffStatus.UNCHANGED).contains(key);
        if (innerPath != null) {
            // ---- 归档内部条目（复合键 outer!/inner）：直接对字节做对应 diff（委托 ArchiveTree）----
            if (foldBuildNoise) {
                return new FrontendTextDiff().diffBytes(
                        normalizeBuildNoise(ArchiveTree.readInnerEntryBytes(job.getOldSnap(), parts).orElse(null)),
                        normalizeBuildNoise(ArchiveTree.readInnerEntryBytes(job.getNewSnap(), parts).orElse(null)),
                        innerPath, fc, rules);
            }
            return ArchiveTree.computeInnerEntry(job.getOldSnap(), job.getNewSnap(), job.opts, key, cfrPath(job.opts), findJava());
        }
        // ---- 顶层条目 ----
        LogicalEntry oe = resolveOldEntry(job, topKey);
        LogicalEntry ne = resolveNewEntry(job, topKey);
        if (fc == FileClass.CLASS) {
            return new Decompiler(cfrPath(job.opts), findJava()).decompile(job.getOldSnap(), job.getNewSnap(), oe, ne, key, rules);
        }
        if (fc == FileClass.ARCHIVE || fc == FileClass.JAR) {
            // 归档（含嵌套 jar）：清单对比。细节委托 diffArchive（见其方法注释），降低本方法认知复杂度。
            return diffArchive(job, key, oe, ne);
        }
        if (fc == FileClass.OFFICE) {
            // Office 文档（docx/xlsx/pptx）：解析内容为文本后行级 diff（需求：文档内容对比）
            return new com.bempdiff.diff.OfficeTextDiff().diff(job.getOldSnap(), job.getNewSnap(), oe, ne, key, fc, rules);
        }
        if (foldBuildNoise) {
            // 顶层文本构建元数据：读两侧字节（含版本改名配对的反查翻译）归一化后 diff
            return new FrontendTextDiff().diffBytes(
                    normalizeBuildNoise(readEntrySafe(job.getOldSnap(), oe)),
                    normalizeBuildNoise(readEntrySafe(job.getNewSnap(), ne)),
                    key, fc, rules);
        }
        return new FrontendTextDiff().diff(job.getOldSnap(), job.getNewSnap(), oe, ne, key, fc, rules);
    }

    /** 读条目字节（缺侧/读取失败返回 null，供归一化 diff 安全处理，与 FrontendTextDiff 缺侧语义一致）。 */
    private static byte[] readEntrySafe(PackageSnapshot snap, LogicalEntry e) {
        if (e == null) return null; // NOSONAR java:S1168 — null 是刻意的「缺侧」语义，下游按缺侧处理，空数组会误判为空文件
        try {
            return new PackageParser().readEntryBytes(snap, e);
        } catch (Exception ignored) {
            return null; // NOSONAR java:S1168 — 同上：读取失败按缺侧语义返回 null，与 FrontendTextDiff 约定一致
        }
    }

    /** 构建元数据噪声归一化（树状态折叠为未变的条目，展开视图口径对齐）。 */
    private static byte[] normalizeBuildNoise(byte[] b) {
        return DiffEngine.normalizeBuildNoise(b);
    }

    /** 归档（含嵌套 jar）条目清单对比：必要时从包内抽取字节落临时文件后走 ArchiveDiff。
     *  提取自 computeUnitByKey 以降低其认知复杂度；临时文件仅在本方法内删除（真实归档绝不删）。 */
    private DecompiledUnit diffArchive(Job job, String key, LogicalEntry oe, LogicalEntry ne) throws IOException {
        Path oa = archivePath(oe);
        Path na = archivePath(ne);
        boolean oaTemp = false;
        boolean naTemp = false; // 仅本请求新建的临时落盘文件才允许删除（真实归档绝不删）
        if (oa != null && !Files.isRegularFile(oa)) oa = null;
        if (na != null && !Files.isRegularFile(na)) na = null;
        if (oa == null && oe != null) { byte[] b = new PackageParser().readEntryBytes(job.getOldSnap(), oe); if (b != null) { oa = writeTemp(b); oaTemp = true; } }
        if (na == null && ne != null) { byte[] b = new PackageParser().readEntryBytes(job.getNewSnap(), ne); if (b != null) { na = writeTemp(b); naTemp = true; } }
        try {
            return new com.bempdiff.diff.ArchiveDiff().diff(key, oa, na);
        } finally {
            // 仅删除本请求新建的临时文件；oa/na 为真实归档路径（folder 模式）时标记为 false，绝不误删用户文件（修正 S1 修复的数据丢失隐患）
            if (oaTemp) deleteTemp(oa);
            if (naTemp) deleteTemp(na);
        }
    }

    /**
     * 差异树右键菜单的磁盘文件操作（仅文件夹对比模式）。
     * POST /api/file  body: { jobId, op: "info"|"delete"|"rename"|"copy", key, newName? }
     * - 包对比模式（job.mode != folder）：返回 400 明确提示"条目位于压缩包内，无磁盘路径"。
     * - 操作侧判定：DELETED→左侧；ADDED→右侧；其余→左侧（基准侧）；复制方向与目标侧相反。
     * - 成功/失败统一 200 + {ok:boolean, code, message, side?}，由前端 toast 呈现（FileOps 纯逻辑可单测）。
     */
    private void handleFile(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "仅支持 POST");
            return;
        }
        String body = readBody(ex);
        Map<String, Object> req = body.isEmpty() ? new LinkedHashMap<>() : Json.parseObject(body);
        String jobId = Json.str(req, KEY_JOB_ID, null);
        String key = Json.str(req, "key", null);
        String op = Json.str(req, "op", null);
        if (jobId == null || key == null || op == null) {
            sendError(ex, 400, "缺少 jobId / key / op");
            return;
        }
        Job job = store.get(jobId);
        if (job == null) {
            sendError(ex, 404, MSG_TASK_NOT_FOUND + jobId);
            return;
        }
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        if (!MODE_FOLDER.equals(job.mode)) {
            // 包对比模式：条目在压缩包内，物理路径不存在 → 明确提示（不静默失败）
            sendError(ex, 400, "当前为包对比模式，条目位于压缩包内（无磁盘路径），不支持该文件操作；请切换到「文件夹」模式后重试");
            return;
        }
        try {
            Path oldRoot = job.getOldSnap().getFile().toAbsolutePath().normalize();
            Path newRoot = job.getNewSnap().getFile().toAbsolutePath().normalize();
            String status = statusOf(job, key);
            // 操作侧判定委派 sideOf 助手（取代嵌套三元，规避 S3358），降低本方法认知复杂度
            String side = sideOf(status);
            Path root = "left".equals(side) ? oldRoot : newRoot;
            Path other = "left".equals(side) ? newRoot : oldRoot;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("key", key);
            resp.put("side", side);
            resp.put(KEY_STATUS, status);
            if (!applyFileOperation(op, key, status, root, other, req, resp)) {
                sendError(ex, 400, "未知文件操作: " + op);
                return;
            }
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "文件操作失败: " + op + " / " + key);
            sendError(ex, 500, "文件操作失败: " + e.getMessage());
        }
    }

    /** 操作侧判定：DELETED→左侧；ADDED→右侧；其余→左侧（独立助手取代嵌套三元，规避 S3358）。 */
    private static String sideOf(String status) {
        if (DiffStatus.DELETED.name().equals(status)) return "left";
        if (DiffStatus.ADDED.name().equals(status)) return "right";
        return "left";
    }

    /** 按 op 分发 FileOps 操作；未知 op 返回 false（由调用方发送未知操作错误）。从 handleFile 抽出以降低其认知复杂度。 */
    private static boolean applyFileOperation(String op, String key, String status, Path root, Path other,
                                              Map<String, Object> req, Map<String, Object> resp) {
        switch (op) {
            case "info" -> {
                FileOps.OpResult r = FileOps.info(root, key);
                resp.put("ok", r.ok());
                resp.put("code", r.code());
                resp.put(KEY_MESSAGE, r.message());
                if (r.info() != null) resp.put("info", r.info().toMap());
            }
            case "delete" -> {
                FileOps.OpResult r = FileOps.deleteEntry(root, key);
                resp.put("ok", r.ok());
                resp.put("code", r.code());
                resp.put(KEY_MESSAGE, r.message());
            }
            case "rename" -> {
                String newName = Json.str(req, "newName", null);
                FileOps.OpResult r = FileOps.renameEntry(root, key, newName);
                resp.put("ok", r.ok());
                resp.put("code", r.code());
                resp.put(KEY_MESSAGE, r.message());
            }
            case "copy" -> {
                // 复制方向：仅右侧存在（ADDED）→ 右→左；其余 → 左→右
                String direction = DiffStatus.ADDED.name().equals(status) ? "r2l" : "l2r";
                FileOps.OpResult r = FileOps.copyAcross(root, other, key, direction);
                resp.put("ok", r.ok());
                resp.put("code", r.code());
                resp.put(KEY_MESSAGE, r.message());
                resp.put("direction", direction);
            }
            default -> { return false; }
        }
        return true;
    }

    /** 查询 key 在差异结果中的状态（MODIFIED/ADDED/DELETED/UNCHANGED）。 */
    private static String statusOf(Job job, String key) {
        for (DiffStatus st : DiffStatus.values()) {
            if (job.getResult().get(st).contains(key)) return st.name();
        }
        return DiffStatus.UNCHANGED.name();
    }

    /**
     * 通用前置校验：读取 jobId/key 参数，校验任务存在与 DONE 状态。
     * 校验失败时已发送对应错误响应并返回 null，调用方据此短路返回。
     * 供 entry 子操作（decompile/children/recursive）复用，避免三处重复样板。
     */
    private Job requireDoneJobWithKey(HttpExchange ex) throws IOException {
        String jobId = queryParam(ex.getRequestURI(), KEY_JOB_ID);
        String key = queryParam(ex.getRequestURI(), "key");
        if (jobId == null || key == null) {
            sendError(ex, 400, "缺少 jobId / key");
            return null;
        }
        Job job = store.get(jobId);
        if (job == null) {
            sendError(ex, 404, MSG_TASK_NOT_FOUND + jobId);
            return null;
        }
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return null;
        }
        return job;
    }

    /**
     * 展开归档：返回内部条目列表，并跨旧/新两侧计算逐文件 ADDED/DELETED/MODIFIED/UNCHANGED。
     * key 可为顶层归档 key，或复合键 outer!/innerArchive（支持递归展开）。
     */
    private void handleEntryChildren(HttpExchange ex) throws IOException {
        Job job = requireDoneJobWithKey(ex);
        if (job == null) return;
        String key = queryParam(ex.getRequestURI(), "key");
        String childrenCacheKey = job.id + "\u0000" + key;
        Object childrenCached = archiveTreeCache.get(childrenCacheKey);
        if (childrenCached != null) { sendJson(ex, 200, childrenCached); return; } // P0-B：命中归档展开缓存
        try {
            List<Map<String, Object>> children = ArchiveTree.computeChildren(job.getOldSnap(), job.getNewSnap(), key, job.opts.getIgnoreExtensions());
            archiveTreeCache.put(childrenCacheKey, children); // T00427：LRU 自动逐出，无需全清
            sendJson(ex, 200, children);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "展开归档失败: " + key);
            sendError(ex, 500, "展开归档失败: " + e.getMessage());
        }
    }

    /**
     * 自动递归解包：把归档（含任意深度嵌套归档）一次性展开为完整嵌套差异树。
     * 每层节点带 status（ADDED/DELETED/MODIFIED/UNCHANGED），嵌套归档节点带 children。
     */
    private void handleEntryRecursive(HttpExchange ex) throws IOException {
        Job job = requireDoneJobWithKey(ex);
        if (job == null) return;
        String key = queryParam(ex.getRequestURI(), "key");
        String recursiveCacheKey = job.id + "\u0000" + key;
        Object recursiveCached = archiveTreeCache.get(recursiveCacheKey);
        if (recursiveCached != null) { sendJson(ex, 200, recursiveCached); return; } // P0-B：命中归档展开缓存
        try {
            Map<String, Object> tree = ArchiveTree.recursiveUnpack(job.getOldSnap(), job.getNewSnap(), key, job.opts.getIgnoreExtensions());
            archiveTreeCache.put(recursiveCacheKey, tree); // T00427：LRU 自动逐出，无需全清
            sendJson(ex, 200, tree);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "递归解包失败: " + key);
            sendError(ex, 500, "递归解包失败: " + e.getMessage());
        }
    }

    private static Path writeTemp(byte[] b) throws IOException {
        Path tmp = Files.createTempFile("bempdiff-entry-", ".bin"); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
        Files.write(tmp, b);
        // S1 修复：兜底在 JVM 退出时删除，避免 %TEMP% 持续累积（调用方也会显式删除）。
        // 空 catch：deleteOnExit 仅为保险性兜底，注册失败可忽略，不阻断正常流程。
        try { tmp.toFile().deleteOnExit(); } catch (Exception ignored) { /* 忽略退出清理注册失败 */ }
        return tmp;
    }

    /** S1 修复：安全删除临时文件（失败静默，不干扰主流程）。 */
    private static void deleteTemp(Path p) {
        if (p == null) return;
        // 空 catch：删除失败（文件句柄被占用/权限不足）时静默，临时残留不阻断主流程。
        try { Files.deleteIfExists(p); } catch (Exception ignored) { /* 删除失败静默跳过 */ }
    }

    /**
     * 递归删除临时目录树（导出/解包等临时产物）。
     * JRE 无 Files.walk，用文件流列表自底向上删除；任一层失败即静默（与 deleteTemp 同风格），
     * 不因清理问题干扰主流程——宁可残留也不让导出因删除失败而报错。
     */
    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try {
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isDirectory(p)) deleteTree(p);
                    else tryDeleteFile(p);
                }
            }
            Files.deleteIfExists(dir);
            // 空 catch：清理任一步骤失败即整体静默跳过（见方法头注释），不因残留中断主流程。
        } catch (Exception ignored) { /* 清理失败静默，宁可残留也不报错 */ }
    }

    /** 静默删除单个文件（从 deleteTree 内层 try 抽出，消除 S1141 嵌套 try；失败静默同清理其余步骤风格）。 */
    private static void tryDeleteFile(Path p) {
        // 空 catch：单文件删除失败不阻断整树清理（残留由后续清理轮次兜底）。
        try { Files.deleteIfExists(p); } catch (Exception ignored) { /* 单文件删除失败静默 */ }
    }


    private static FileClass entryFileClass(LogicalEntry oe, LogicalEntry ne) {
        if (ne != null) return ne.getFileClass();
        if (oe != null) return oe.getFileClass();
        return FileClass.CLASS;
    }

    /** 把 LogicalEntry 还原为磁盘上的归档文件路径（仅对"非嵌套"条目有效）。 */
    private static java.nio.file.Path archivePath(LogicalEntry e) {
        if (e == null || e.getSrc() == null) return null;
        if (e.getSrc().isNested()) return null; // 嵌套条目（如 war 内的 lib jar）不直接走 ArchiveDiff
        String outer = e.getSrc().getOuterEntry();
        if (outer == null) return null;
        return java.nio.file.Paths.get(outer);
    }

    // ---------------- 配置 ----------------

    private void handleConfig(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        if (ex.getRequestMethod().equals("GET")) {
            sendJson(ex, 200, config.toJson());
            return;
        }
        if (ex.getRequestMethod().equals("PUT")) {
            Map<String, Object> req = Json.parseObject(readBody(ex));
            config.updateFromAsync(req); // P1-F：异步合并落盘，PUT 不再阻塞于磁盘写（p50 90-178ms → 内存级）
            sendJson(ex, 200, config.toJson());
            return;
        }
        sendError(ex, 405, "不支持的方法: " + ex.getRequestMethod());
    }

    // ---------------- 临时文件清理（手动 + 退出兜底） ----------------

    /**
     * 清理跳过最近 30s 内仍有变动的临时文件/目录：它们可能正在被解包、导出或下发，
     * 立即删除会截断进行中的响应（声明了 Content-Length 却读不到数据 → 客户端挂到 60s）。
     */
    private static final long CLEANUP_FRESH_MS = 30_000;

    /** 清理结果（供 API 返回）。 */
    public static final class CleanupResult {
        public final long freedBytes;
        public final int files;
        public final int dirs;
        CleanupResult(long freedBytes, int files, int dirs) {
            this.freedBytes = freedBytes;
            this.files = files;
            this.dirs = dirs;
        }
    }

    /**
     * POST /api/admin/cleanup-temp：配置中心「手动清理」——立即回收解压/抽取临时文件与遗留运行时目录，
     * 返回 { ok, freedBytes, files, dirs }。供用户磁盘紧张时按需触发，也可被退出 hook 复用。
     */
    private void handleCleanupTemp(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        try {
            CleanupResult r = cleanupTempFiles();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("freedBytes", r.freedBytes);
            resp.put(KEY_FILES, r.files);
            resp.put("dirs", r.dirs);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "清理临时文件失败", e);
            sendError(ex, 500, "清理临时文件失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/admin/diag-bundle：FR-011 一键导出脱敏诊断包（环境信息 + 脱敏日志 zip）。
     * 包体 <5MB（DiagBundle 内护栏），生成到系统临时目录下的专属子目录（bmddiag- 前缀，
     * 不进入 cleanupTempFiles 的 bempdiff-* 白名单），流式下发后立即删除。
     */
    private void handleDiagBundle(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            sendError(ex, 405, "仅支持 GET");
            return;
        }
        Path work = null;
        try {
            Path osTemp = Paths.get(System.getProperty("java.io.tmpdir", "."));
            // 诊断包与 cleanupTempFiles 共用系统临时目录，但清理会递归回收 bempdiff-* 前缀；
            // 若 zip 直接落在临时目录根，自动清理可能删掉正在下发的文件（Content-Length 无法
            // 满足 → 客户端挂到 60s 超时）。放进非 bempdiff- 前缀的专属子目录，清理不会命中，
            // 由 finally 在响应结束后整体回收。
            work = Files.createTempDirectory(osTemp, "bmddiag-");
            Path zip = DiagBundle.build(work);
            ex.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"bempdiff-diag-"
                            + java.time.LocalDate.now() + ".zip\"");
            sendFile(ex, 200, "application/zip", zip);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "诊断包生成失败", e);
            // sendFile 内部已写响应头时不能再 sendError（HttpServer 会抛 IllegalStateException），
            // 此时只能放弃错误提示——生成失败发生在 build 阶段才有机会返回 JSON 错误体
            if (ex.getResponseHeaders().getFirst(HDR_CONTENT_TYPE) == null) {
                sendError(ex, 500, "诊断包生成失败: " + e.getMessage());
            }
        } finally {
            // zip 随专属目录一并回收，不依赖退出 hook
            deleteTree(work);
        }
    }

    /**
     * 全量回收临时文件（手动清理按钮与退出 hook 共用同一入口，保证行为一致）。
     * 范围（白名单，绝不触碰无关文件）：
     *  - 系统临时目录下所有 bempdiff-* 前缀文件与目录（含解包/测试/导出等残留）；
     *  - 无任何在册 job 引用的 .bempdiff/runtime 孤儿目录（进行中解压的 job 持目录引用 → 自动跳过，不中断任务）；
     *  - 超过 3 天的运行日志。
     * 不回收反编译缓存 decompile-cache（有重建价值的缓存，交由自身 LRU/容量机制管理）。
     * 单文件/目录失败静默跳过，绝不因个别占用中断整体清理。
     */
    private CleanupResult cleanupTempFiles() {
        // 三段回收彼此独立，分别提取为私有方法以降低单体认知复杂度；结果以 {freed,files,dirs} 汇总。
        long[] t = cleanupSystemTemp();
        long[] rt = cleanupOrphanRuntime();
        long[] lg = cleanupOldLogs();
        return new CleanupResult(t[0] + rt[0] + lg[0],
                (int) (t[1] + rt[1] + lg[1]), (int) (t[2] + rt[2] + lg[2]));
    }

    /** 段1：回收系统临时目录下 bempdiff-* 残留。返回 {freedBytes, fileCount, dirCount}。 */
    private long[] cleanupSystemTemp() {
        long[] st = {0, 0, 0}; // freedBytes, fileCount, dirCount 三元素
        String osTemp = System.getProperty("java.io.tmpdir");
        if (osTemp == null) return st;
        Path t = Paths.get(osTemp);
        if (!Files.isDirectory(t)) return st;
        try (java.util.stream.Stream<Path> s = Files.list(t)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.startsWith("bempdiff-")) continue; // 白名单前缀，防误删
                // 刚创建/刚写入的产物可能仍被解包或下发流程持有，跳过留给下一轮清理。
                if (System.currentTimeMillis() - lastModifiedMs(p) < CLEANUP_FRESH_MS) continue;
                // 目录统一按 bempdiff- 前缀回收。此前仅认 bempdiff-unpack-*，会漏掉测试残留的
                // bempdiff-folderdiff/logs/ut、解包目录 bempdiff-unpack<随机>(createTempDirectory
                // 前缀无连字符) 等本工具临时产物；前缀白名单已保证只动 BempDiff 自己的目录。
                if (Files.isDirectory(p)) {
                    long sz = treeSizeOf(p);
                    deleteTree(p);
                    if (!Files.exists(p)) { st[0] += sz; st[2]++; }
                } else {
                    // 文件同样按 bempdiff- 前缀回收。扩展不限于 class/bin/jar/zip，否则
                    // bempdiff-*.sh 等临时文件会永久残留(曾被实测漏清)。删除与计数委派 tryDeleteAndCount（消除 S1141 内层 try）。
                    tryDeleteAndCount(p, st);
                }
            }
        } catch (Exception ignored) { /* 目录遍历失败静默，不阻断其余段清理 */ }
        return st;
    }

    /** 段2：回收无在册 job 引用的 runtime 孤儿目录。返回 {freedBytes, fileCount, dirCount}。 */
    private long[] cleanupOrphanRuntime() {
        long freed = 0;
        long dirs = 0;
        Path root = runtimeRoot();
        if (root == null || !Files.isDirectory(root)) return new long[]{0, 0, 0};
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            for (Path child : (Iterable<Path>) s::iterator) {
                // 进行中的 job 目录因「有引用」被跳过，不中断任务
                if (!Files.isDirectory(child)) continue;
                if (!hasActiveJob(child)) {
                    long sz = treeSizeOf(child);
                    deleteTree(child);
                    if (!Files.exists(child)) { freed += sz; dirs++; }
                }
            }
        } catch (Exception ignored) { /* 目录遍历失败静默，不阻断其余段清理 */ }
        return new long[]{freed, 0, dirs};
    }

    /** 该 runtime 子目录是否被在册 job 引用（被引用即进行中，跳过回收）。提取自 cleanupOrphanRuntime 以降低认知复杂度。 */
    private boolean hasActiveJob(Path child) {
        for (Job j : store.all().values()) {
            Path rd = j.getRuntimeDir();
            if (rd != null && rd.normalize().equals(child.normalize())) return true;
        }
        return false;
    }

    /** 段3：回收超过 3 天的运行日志（保留近期用于问题回溯）。返回 {freedBytes, fileCount, dirCount}。 */
    private long[] cleanupOldLogs() {
        long[] st = {0, 0, 0}; // freedBytes, fileCount, dirCount 三元素
        final long keepMs = 3L * 24 * 60 * 60 * 1000;
        Path logs = unpackLogsDir();
        if (!Files.isDirectory(logs)) return st;
        try (java.util.stream.Stream<Path> s = Files.walk(logs)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (System.currentTimeMillis() - lastModifiedMs(p) > keepMs) {
                    tryDeleteAndCount(p, st);
                }
            }
        } catch (Exception ignored) { /* 日志遍历失败静默，不阻断主流程 */ }
        return st;
    }

    /** 删除单个文件并累计释放字节/文件数（提取内层 try 以消除 S1141；失败静默）。stats={freed,files,dirs}。 */
    private static void tryDeleteAndCount(Path p, long[] stats) {
        // 空 catch：单文件删除失败静默，清理其余步骤不受影响。
        long sz = fileSizeOf(p);
        try {
            Files.deleteIfExists(p);
            stats[1]++;
            stats[0] += sz;
        } catch (Exception ignored) { /* 单文件删除失败静默 */ }
    }

    private static long fileSizeOf(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0L; }
    }

    private static long treeSizeOf(Path d) {
        try (java.util.stream.Stream<Path> s = Files.walk(d)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0L; }
            }).sum();
        } catch (Exception e) { return 0L; }
    }

    private static long lastModifiedMs(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return System.currentTimeMillis(); }
    }

    // ---------------- AI 连接测试 ----------------

    private void handleAiTest(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        try {
            AiConfig aiCfg = readAiTestConfig(ex);
            HttpAiAnalyzer analyzer = new HttpAiAnalyzer(aiCfg);
            boolean ok = analyzer.testConnection(aiCfg);
            String msg = ok ? "连接成功" : errorText(analyzer.getLastError());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", ok);
            resp.put(KEY_MESSAGE, msg);
            resp.put(KEY_LAST_ERROR, analyzer.getLastError());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "AI 连接测试异常", e);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put(KEY_MESSAGE, "测试异常: " + e.getMessage());
            resp.put(KEY_LAST_ERROR, e.getMessage());
            sendJson(ex, 200, resp);
        }
    }

    // ---------------- AI 模型列表自动获取 ----------------

    /**
     * 根据 API Base URL + API Key 自动获取可用模型列表（OpenAI 兼容 /models 接口）。
     * 请求体可携带 provider/baseUrl/apiKey/httpProxy/httpsProxy/blockPrivateEndpoints 临时覆盖，
     * 缺省时回退服务端已保存配置（与 handleAiTest 同一读取逻辑）。返回 {ok, models[], lastError, message}。
     */
    private void handleAiModels(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        try {
            AiConfig aiCfg = readAiTestConfig(ex);
            HttpAiAnalyzer analyzer = new HttpAiAnalyzer(aiCfg);
            List<String> models = analyzer.fetchModels(aiCfg);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", !models.isEmpty());
            resp.put("models", models);
            resp.put(KEY_LAST_ERROR, analyzer.getLastError());
            // 内层三元展开为 if/else（规避 S3358 嵌套三元），行为不变
            if (models.isEmpty()) {
                resp.put(KEY_MESSAGE, analyzer.getLastError() != null ? analyzer.getLastError() : "未获取到模型列表");
            } else {
                resp.put(KEY_MESSAGE, "已获取 " + models.size() + " 个可用模型");
            }
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "获取模型列表异常", e);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put("models", new ArrayList<>());
            resp.put(KEY_LAST_ERROR, e.getMessage());
            resp.put(KEY_MESSAGE, "获取模型列表异常: " + e.getMessage());
            sendJson(ex, 200, resp);
        }
    }

    // ---------------- 「关于」页版本更新检查 ----------------

    /**
     * POST /api/update/check  body {current?: string}
     * 查询 GitHub 最新 Release 并与当前版本比较（详见 UpdateCheckService）。
     * 网络失败/限流均以 ok=false + message 返回（HTTP 仍为 200），前端按需展示排查提示。
     */
    private void handleUpdateCheck(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        try {
            String current = "";
            if (ex.getRequestMethod().equals("POST")) {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                current = Json.str(req, "current", "");
            }
            sendJson(ex, 200, UpdateCheckService.check(current, config.getGithubToken()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "版本更新检查异常", e);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put("upToDate", null);
            resp.put("latest", null);
            resp.put(KEY_MESSAGE, "版本更新检查异常: " + e.getMessage());
            sendJson(ex, 200, resp);
        }
    }

    // ---------------- 项目级上下文（递归识别 + 缓存）管理 ----------------

    /**
     * 查询/刷新「上下文目录」的递归项目索引。
     * GET /api/ai/context?dir=<projDir>&refresh=1 ；POST body {dir, refresh} 亦可。
     * 返回项目清单（relPath/构建系统/模块数/依赖数/简述）与缓存信息，供配置中心展示与手动刷新。
     */
    private void handleAiContext(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        try {
            String dir = "";
            boolean refresh = false;
            if (ex.getRequestMethod().equals("GET")) {
                Map<String, String> q = queryOf(ex.getRequestURI().getQuery());
                dir = q.getOrDefault("dir", "");
                refresh = "1".equals(q.get(KEY_REFRESH)) || "true".equals(q.get(KEY_REFRESH));
            } else if (ex.getRequestMethod().equals("POST")) {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                dir = Json.str(req, "dir", "");
                refresh = Json.bool(req, KEY_REFRESH, false);
            }
            if (dir.isEmpty()) dir = config.getProjectContextDir();
            if (dir == null || dir.isEmpty()) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", false);
                resp.put(KEY_MESSAGE, "未指定 dir 且配置中心未设置项目上下文目录");
                sendJson(ex, 200, resp);
                return;
            }
            boolean fromCache;
            ProjectIndex idx = refresh ? ProjectContextService.refresh(dir)
                    : ProjectContextService.resolve(dir);
            if (idx == null) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", false);
                resp.put(KEY_MESSAGE, "目录无效：\"" + dir + "\"");
                sendJson(ex, 200, resp);
                return;
            }
            // fromCache：显式刷新后必为 false；resolve 命中内存/磁盘缓存时为 true
            fromCache = !refresh && ProjectContextCache.load(dir) != null;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.putAll(ProjectContextCache.summaryOf(idx, fromCache));
            resp.putAll(buildProjectList(idx));
            resp.put(KEY_MESSAGE, (refresh ? "已刷新" : "已加载") + " " + idx.size() + " 个项目上下文");
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "查询项目上下文异常", e);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put(KEY_MESSAGE, "查询项目上下文异常: " + e.getMessage());
            sendJson(ex, 200, resp);
        }
    }

    /** 汇总项目清单与 Java 文件总数（提取自 handleAiContext 以降低其认知复杂度）。 */
    private static Map<String, Object> buildProjectList(ProjectIndex idx) {
        List<Map<String, Object>> projects = new ArrayList<>();
        int totalJava = 0;
        for (ProjectIndex.ProjectEntry e : idx.getProjects()) {
            ProjectContext c = e.getCtx();
            if (c == null) continue;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("relPath", e.getRelPath());
            pm.put("buildSystem", c.getBuildSystem());
            pm.put("moduleCount", c.getModules() == null ? 0 : c.getModules().size());
            pm.put("depCount", c.getDependencies() == null ? 0 : c.getDependencies().size());
            pm.put("fileCount", e.getJavaFileCount());
            if (c.getAnalyzedFiles() > 0) pm.put("analyzedFiles", c.getAnalyzedFiles());
            if (c.isTruncated()) pm.put("truncated", true);
            pm.put("summary", c.getSummary());
            projects.add(pm);
            totalJava += e.getJavaFileCount();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projects", projects);
        m.put("javaFileCount", totalJava);
        return m;
    }

    /** 解析查询串为键值对（dir、refresh 等），容错空值。 */
    private static Map<String, String> queryOf(String query) {
        Map<String, String> m = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return m;
    }

    private AiConfig readAiTestConfig(HttpExchange ex) throws IOException {
        AiConfig aiCfg = config.toAiConfig();
        aiCfg.setEnabled(true);
        if (!(ex.getRequestMethod().equals("POST") && ex.getRequestBody().available() > 0)) {
            return aiCfg;
        }
        Map<String, Object> req = Json.parseObject(readBody(ex));
        if (req.containsKey("provider")) aiCfg.setProvider(Json.str(req, "provider", aiCfg.getProvider()));
        if (req.containsKey("baseUrl")) aiCfg.setBaseUrl(Json.str(req, "baseUrl", aiCfg.getBaseUrl()));
        if (req.containsKey("apiKey")) aiCfg.setApiKey(Json.str(req, "apiKey", ""));
        if (req.containsKey(AI_MODEL_FIELD)) aiCfg.setModel(Json.str(req, AI_MODEL_FIELD, aiCfg.getModel()));
        if (req.containsKey("httpProxy")) aiCfg.setHttpProxy(Json.str(req, "httpProxy", aiCfg.getHttpProxy()));
        if (req.containsKey("httpsProxy")) aiCfg.setHttpsProxy(Json.str(req, "httpsProxy", aiCfg.getHttpsProxy()));
        if (req.containsKey("blockPrivateEndpoints")) aiCfg.setBlockPrivateEndpoints(Json.bool(req, "blockPrivateEndpoints", aiCfg.isBlockPrivateEndpoints()));
        return aiCfg;
    }

    private static String errorText(String lastError) {
        return lastError != null ? lastError : "连接失败";
    }

    // ---------------- 静态资源 ----------------

    private void handleStatic(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals(M_OPTIONS)) {
            sendJson(ex, 204, new LinkedHashMap<>());
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = DEFAULT_INDEX;
        Path file = (webroot != null) ? webroot.resolve(path.substring(1)) : null;
        if (file != null && Files.isRegularFile(file)) {
            serveStaticBytes(ex, path, file);
            return;
        }
        // 占位首页（前端未构建时也能打开看到服务存活）
        String html = "<!doctype html><html lang='zh'><head><meta charset='utf-8'>"
                + "<title>BempDiff Web</title><style>body{font-family:system-ui;margin:40px;background:#f6f8fa}"
                + "code{background:#eef;padding:2px 6px;border-radius:4px}</style></head><body>"
                + "<h1>BempDiff Web 服务</h1><p>服务已启动。可用接口：</p><ul>"
                + "<li><code>POST /api/session/compare</code> 比对（package/folder）</li>"
                + "<li><code>GET /api/job/{id}/status</code> 任务状态/差异树</li>"
                + "<li><code>GET /api/entry/decompile?jobId=&amp;key=</code> 单文件反编译</li>"
                + "<li><code>GET/PUT /api/config</code> 配置</li>"
                + "<li><code>POST /api/ai/test</code> AI 连接测试</li>"
                + "<li><code>POST /api/ai/models</code> 自动获取模型列表</li>"
                + "<li><code>POST /api/job/{id}/report</code> 生成 Markdown 报告</li>"
                + "<li><code>POST /api/job/{id}/export</code> 导出差异资产 zip（同步流式，兼容旧调用）</li>"
                + "<li><code>POST /api/job/{id}/export/start</code> 导出分流：小包同步流式(200 zip)、大包异步(202 {id})</li>"
                + "<li><code>GET /api/export/list</code> 导出记录列表；<code>GET /api/export/{id}</code> 单条状态</li>"
                + "<li><code>GET /api/export/{id}/download</code> 下载已完成的大包导出 zip；<code>POST /api/export/{id}/delete</code> 删除记录</li>"
                + "</ul><p>前端 SPA 构建后放置到 webroot 目录即可被本服务托管。</p></body></html>";
        sendText(ex, 200, "text/html", html);
    }

    private static String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    /** P0-C：下发 webroot 静态文件，命中内存缓存则跳过磁盘读取。同时附加 Cache-Control 头（见方法体注释）。 */
    private void serveStaticBytes(HttpExchange ex, String path, Path file) throws IOException {
        StaticEntry entry;
        // 惰性校验：先取零成本 stat（mtime+size）判断文件是否变化，未变则直接复用内存字节，避免每次整文件 readAll。
        try {
            long lm = Files.getLastModifiedTime(file).toMillis();
            long sz = Files.size(file);
            StaticEntry e = staticCache.get(path);
            if (e != null && e.lastModified == lm && e.size == sz) {
                entry = e; // 命中缓存：跳过磁盘读取
            } else {
                entry = new StaticEntry(readAll(Files.newInputStream(file)), mimeOf(path), lm, sz);
                staticCache.put(path, entry); // T00427：LRU 自动逐出，无需全清
            }
        } catch (IOException statFail) {
            // stat/读取瞬时失败：退化为现场读一次并直接下发（与缓存前旧行为一致），不因缓存错误拒绝服务
            entry = new StaticEntry(readAll(Files.newInputStream(file)), mimeOf(path), 0L, -1L);
        }
        // index.html 为 SPA 骨架页，须随前端构建刷新，浏览器端不缓存；
        // 其余静态资产（js/css/图片等）允许浏览器长缓存，减少重复回源。
        ex.getResponseHeaders().set("Cache-Control",
                DEFAULT_INDEX.equals(path) ? "no-cache" : "public, max-age=604800");
        sendBytesInline(ex, 200, entry.contentType, entry.data);
    }

    // ---------------- AI 智能分类与优先级（A1+B2） ----------------

    /**
     * 智能分类与优先级（B2 自动打标 + A1 风险分级）。
     * 复用两阶段管线的 stageB（同样受成本闸门/脱敏约束），对每个候选文件产出
     * { key, risk(HIGH/MEDIUM/LOW), category, reason }。
     * 无 API Key（Mock 模式）时 risk 走确定性启发式、category 始终启发式，保证离线可验证。
     */
    private void handleClassify(HttpExchange ex, Job job) throws IOException { // NOSONAR(S3776, S6541) - 分类请求处理，双路径+嵌套分支为业务必需，拆分降低可读性
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        try {
            String projDir = null;
            if (ex.getRequestMethod().equals("POST")) {
                String body = readBody(ex);
                if (!body.isEmpty()) {
                    Map<String, Object> req = Json.parseObject(body);
                    projDir = Json.str(req, KEY_PROJECT_DIR, null);
                }
            }
            // 分类需覆盖全部变更文件（不局限于 L1 内部类），故直接对 MODIFIED/ADDED/DELETED 反编译/文本化，
            // 不依赖 buildClassMap 的 L1 过滤。反编译失败（ok=false）仍保留单元，启发式打标不受影响。
            Decompiler dec = new Decompiler(cfrPath(job.opts), findJava());
            FrontendTextDiff ftd = new FrontendTextDiff();
            com.bempdiff.diff.DiffRules rules = job.opts.toDiffRules();
            Map<String, DecompiledUnit> aiMap = new LinkedHashMap<>();
            for (DiffStatus st : new DiffStatus[]{DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED}) {
                for (String k : job.getResult().get(st)) {
                    LogicalEntry oe = job.getOldSnap().getEntries().get(k);
                    LogicalEntry ne = job.getNewSnap().getEntries().get(k);
                    FileClass fc = fileClassOfKey(k, job.getOldSnap(), job.getNewSnap());
                    DecompiledUnit u = (fc == FileClass.CLASS)
                            ? dec.decompile(job.getOldSnap(), job.getNewSnap(), oe, ne, k, rules)
                            : ftd.diff(job.getOldSnap(), job.getNewSnap(), oe, ne, k, fc, rules);
                    aiMap.put(k, u);
                }
            }
            ProjectContext ctx = (projDir != null && !projDir.isEmpty())
                    ? resolveContextView(projDir) : null;
            AiConfig aiCfg = config.toAiConfig();
            boolean realLlm = aiCfg.getApiKey() != null && !aiCfg.getApiKey().isEmpty();
            AiAnalyzer analyzer = realLlm ? new HttpAiAnalyzer(aiCfg)
                    : new MockAiAnalyzer(Paths.get(System.getProperty(PROP_USER_HOME), DIR_BEMPDIFF, DIR_AI_REPLAY));
            // 修复：智能分类覆盖全部变更文件，不按 stageBTopK 截断。
            // 此前复用报告/分析的 topK 截断（默认 15），导致差异树只对前 N 个文件打标、
            // 其余文件无分类（用户反馈「分类不全」）。候选顺序保持 aiMap 插入序（ADDED/MODIFIED/DELETED）。
            List<AiAnalyzer.DecompileReq> cands = buildAllCandidates(aiMap, job);
            List<FileAnalysis> b = analyzer.stageB(cands, aiCfg, ctx);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < cands.size(); i++) {
                AiAnalyzer.DecompileReq req = cands.get(i);
                FileAnalysis fa = (i < b.size()) ? b.get(i) : null;
                DiffStatus st = statusOf(job.getResult(), req.key);
                String rawRisk = (fa != null) ? fa.getRisk() : null;
                String risk = realLlm ? normalizeRisk(rawRisk)
                                       : heuristicRisk(req.key, req.fileClass);
                String category = categorize(req.key, req.fileClass, fa != null ? fa.getIntent() : null);
                String reason = (fa != null && fa.getIntent() != null && !fa.getIntent().isEmpty())
                        ? fa.getIntent() : heuristicReason(req.fileClass, st);
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("key", req.key);
                it.put("risk", risk);
                it.put(KEY_CATEGORY, category);
                it.put("reason", reason);
                items.add(it);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("items", items);
            resp.put("coverage", cands.size());
            resp.put("totalChanged", job.getResult().get(DiffStatus.ADDED).size()
                    + job.getResult().get(DiffStatus.MODIFIED).size() + job.getResult().get(DiffStatus.DELETED).size());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "智能分类失败", e);
            sendError(ex, 500, "智能分类失败: " + e.getMessage());
        }
    }

    // ---------------- 成本闸门：AI token 预估（P0 #5） ----------------

    /**
     * AI 成本预估端点：复用两阶段管线的 prompt 构造（PromptBuilders.buildStageA/B）+ estimateTokens
     * （prompt 字符数 / 2），精确预估三大 AI 入口的 token 消耗，供前端超阈值强制确认。
     *  - report / analyze：共用 runAiAnalysis 管线 → stageA 一次 + stageB 逐文件
     *  - classify：仅 stageB 逐文件（无 stageA）
     * 带 per-job 缓存，避免重复反编译。
     */
    private void handleAiEstimate(HttpExchange ex, Job job) throws IOException {
        if (!"DONE".equals(job.getStatus())) {
            sendError(ex, 409, MSG_NOT_DONE + job.getStatus());
            return;
        }
        try {
            AiConfig aiCfg = config.toAiConfig();
            // 修复：预估按分析项区分（与 report/ai-analyze 真实 prompt 口径一致）。
            // 缓存键含类别：不同分析项 prompt 不同、token 预估不同，必须分开缓存。
            String category = null;
            String prompt = null;
            String projDir = null;
            String fileKey = null;
            if (ex.getRequestMethod().equals("POST")) {
                String body = readBody(ex);
                if (!body.isEmpty()) {
                    Map<String, Object> req = Json.parseObject(body);
                    category = Json.str(req, KEY_CATEGORY, null);
                    prompt = Json.str(req, KEY_PROMPT, null);
                    projDir = Json.str(req, KEY_PROJECT_DIR, null);
                    fileKey = Json.str(req, "fileKey", null);
                }
            }
            // 前端未显式传 projectDir 时回退到配置中心的项目上下文设置（与 runAiAnalysis 口径一致）。
            if (projDir == null && config.isProjectContextEnabled()) projDir = config.getProjectContextDir();
            // 单文件「AI功能总结」：analyze 预估仅算该文件的 stageB，避免按全量估算误弹成本确认框。
            // 缓存键含阈值与 prompt（评审 M3）：与全量分支同策略，两者任一变化即失效重算，
            // 否则热更新阈值后闸门会被旧预估冻结；computeUnitByKey 对 class/Office 是秒级计算，必须缓存。
            if (fileKey != null && !fileKey.isEmpty()) {
                double threshold = aiCfg.getCostGateWarnTokens();
                String singleKey = job.id + "@" + threshold + "@single@" + fileKey + "@" + prompt;
                Map<String, Object> cachedSingle = aiEstimateCache.get(singleKey);
                if (cachedSingle != null) { sendJson(ex, 200, cachedSingle); return; }
                Map<String, Object> single = new LinkedHashMap<>();
                single.put("analyze", estimateSingleFile(job, aiCfg, projDir, fileKey, prompt));
                single.put("threshold", threshold);
                aiEstimateCache.put(singleKey, single);
                sendJson(ex, 200, single);
                return;
            }
            String focus = buildFocus(category, prompt);
            // 缓存键含阈值：阈值可热更新，变化时必须失效重算（否则闸门阈值被冻结，与配置脱节）。
            double threshold = aiCfg.getCostGateWarnTokens();
            String cacheKey = job.id + "@" + threshold + "@" + focus;
            Map<String, Object> cached = aiEstimateCache.get(cacheKey);
            if (cached != null) { sendJson(ex, 200, cached); return; }
            Map<String, Object> r = new LinkedHashMap<>();
            // M4 修复：report/analyze 的 ctx 须与真实 runAiAnalysis 口径一致（按 projDir 推导），
            // 此前传 null 导致启用「项目级上下文增强」时预估低于实际消耗（成本闸门可能漏拦）。
            ProjectContext ctx = reportContext(projDir);
            double rep = estimateStageAStageB(job, aiCfg, ctx, focus);
            r.put("report", rep);
            r.put("analyze", rep);
            // classify 的 ctx 与真实调用一致：projectContextEnabled 时注入项目上下文
            r.put("classify", estimateStageBOnly(job, aiCfg, classifyContext()));
            r.put("threshold", threshold);
            aiEstimateCache.put(cacheKey, r);
            sendJson(ex, 200, r);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "AI 成本预估失败", e);
            sendError(ex, 500, "AI 成本预估失败: " + e.getMessage());
        }
    }

    /** classify 真实调用使用的项目上下文：仅当 projectContextEnabled 且目录非空时注入（与 runAiClassify 决策一致）。 */
    private ProjectContext classifyContext() {
        if (config.isProjectContextEnabled() && config.getProjectContextDir() != null
                && !config.getProjectContextDir().isEmpty()) {
            try {
                return resolveContextView(config.getProjectContextDir());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "项目上下文分析失败（预估降级为无上下文）", e);
            }
        }
        return null;
    }

    /** M4 修复：report/analyze 预估使用的项目上下文，口径与 runAiAnalysis 完全一致——
     *  优先用本次请求携带的 projectDir，未传则回退到配置中心的项目上下文目录。 */
    private ProjectContext reportContext(String projDir) {
        if (projDir != null && !projDir.isEmpty()) {
            try {
                return resolveContextView(projDir);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "项目上下文分析失败（预估降级为无上下文）", e);
            }
        }
        return null;
    }

    /** 与 AI 主链路同源的上下文视图：多项目索引 + 缓存 + 失效检查。 */
    private static ProjectContext resolveContextView(String projDir) {
        ProjectIndex pIndex = ProjectContextService.resolve(projDir);
        return ProjectContextService.renderContextView(pIndex);
    }

    /** 报告/流式分析：stageA 一次 + stageB 逐候选文件（截断 stageBTopK），汇总 token 估算。 */
    private double estimateStageAStageB(Job job, AiConfig aiCfg, ProjectContext ctx, String focus) {
        Map<String, DecompiledUnit> aiMap = aiMapForReport(job);
        AiAnalyzer analyzer = new MockAiAnalyzer(
                Paths.get(System.getProperty(PROP_USER_HOME), DIR_BEMPDIFF, DIR_AI_REPLAY));
        double tokens = analyzer.estimateTokens(
                analyzer.buildStageAPrompt(job.getResult(), aiMap, aiCfg, ctx, focus, job.getStats()));
        for (AiAnalyzer.DecompileReq req : buildCandidates(aiMap, job, aiCfg.getStageBTopK())) {
            tokens += analyzer.estimateTokens(
                    analyzer.buildStageBPrompt(req.key, req.unit, req.fileClass, aiCfg, ctx, focus));
        }
        return tokens;
    }

    /** 智能分类：仅 stageB 逐候选文件。修复：覆盖全部变更文件（与 handleClassify 一致，
     *  不按 stageBTopK 截断——分类是逐文件打标，截断会使成本预估低于真实消耗、成本闸门漏拦）。 */
    private double estimateStageBOnly(Job job, AiConfig aiCfg, ProjectContext ctx) {
        Map<String, DecompiledUnit> aiMap = aiMapForClassify(job);
        AiAnalyzer analyzer = new MockAiAnalyzer(
                Paths.get(System.getProperty(PROP_USER_HOME), DIR_BEMPDIFF, DIR_AI_REPLAY));
        double tokens = 0;
        for (AiAnalyzer.DecompileReq req : buildAllCandidates(aiMap, job)) {
            tokens += analyzer.estimateTokens(
                    analyzer.buildStageBPrompt(req.key, req.unit, req.fileClass, aiCfg, ctx));
        }
        return tokens;
    }

    /**
     * 单文件「AI功能总结」预估：与 runSingleFileAnalysis 真实调用同口径（评审 M1）——
     * 上下文构造复用 resolve（含缓存）→ renderContextView 全局视图 → locate 逐文件优先注入，
     * 与 HttpAiAnalyzer.stageB 的 effCtx 决策（perFileCtx 优先、缺省回落全局）完全一致；
     * focus 拼接亦同源（singleFileFocus），prompt 不再被预估丢弃（评审 L3）。
     */
    private double estimateSingleFile(Job job, AiConfig aiCfg, String projDir, String fileKey, String prompt) {
        try {
            FileClass fc = fileClassOfTreeKey(job, fileKey);
            if (fc == FileClass.FOLDER) return 0;
            DecompiledUnit unit = computeUnitByKey(job, fileKey);
            if (!unit.isOk()) return 0; // 真实调用会直接拒绝，无需预估
            ProjectIndex pIndex = ProjectContextService.resolve(projDir);
            ProjectContext ctx = ProjectContextService.renderContextView(pIndex);
            ProjectContext effCtx = (pIndex != null) ? pIndex.locate(fileKey) : null;
            if (effCtx == null) effCtx = ctx;
            AiAnalyzer analyzer = new MockAiAnalyzer(
                    Paths.get(System.getProperty(PROP_USER_HOME), DIR_BEMPDIFF, DIR_AI_REPLAY));
            return analyzer.estimateTokens(
                    analyzer.buildStageBPrompt(fileKey, unit, fc, aiCfg, effCtx, singleFileFocus(prompt)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "单文件预估失败（按 0 处理放行闸门）", e);
            return 0;
        }
    }

    /** 报告/流式分析共用的 aiMap（L1 内部类 + 前端文本，截断 topK）；与 runAiAnalysis 构造一致。 */
    private Map<String, DecompiledUnit> aiMapForReport(Job job) {
        Map<String, DecompiledUnit> decompiled = buildClassMap(job, job.opts.getTopK());
        Map<String, DecompiledUnit> text = buildTextMap(job, job.opts.getTopK());
        Map<String, DecompiledUnit> m = new LinkedHashMap<>(decompiled);
        m.putAll(text);
        return m;
    }

    /** 智能分类共用的 aiMap（覆盖全部 MODIFIED/ADDED/DELETED，不局限于 L1）；与 handleClassify 构造一致。 */
    private Map<String, DecompiledUnit> aiMapForClassify(Job job) {
        Decompiler dec = new Decompiler(cfrPath(job.opts), findJava());
        FrontendTextDiff ftd = new FrontendTextDiff();
        com.bempdiff.diff.DiffRules rules = job.opts.toDiffRules();
        Map<String, DecompiledUnit> aiMap = new LinkedHashMap<>();
        for (DiffStatus st : new DiffStatus[]{DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED}) {
            for (String key : job.getResult().get(st)) {
                LogicalEntry oe = resolveOldEntry(job, key);
                LogicalEntry ne = resolveNewEntry(job, key);
                FileClass fc = fileClassOfKey(key, job.getOldSnap(), job.getNewSnap());
                DecompiledUnit u = (fc == FileClass.CLASS)
                        ? dec.decompile(job.getOldSnap(), job.getNewSnap(), oe, ne, key, rules)
                        : ftd.diff(job.getOldSnap(), job.getNewSnap(), oe, ne, key, fc, rules);
                aiMap.put(key, u);
            }
        }
        return aiMap;
    }

    /** 按 aiMap 构造候选（与 runAiAnalysis 候选顺序、截断一致），用于 report/analyze 的 stageB 估算。 */
    private List<AiAnalyzer.DecompileReq> buildCandidates(
            Map<String, DecompiledUnit> aiMap, Job job, int topK) {
        List<AiAnalyzer.DecompileReq> cands = new ArrayList<>();
        for (Map.Entry<String, DecompiledUnit> en : aiMap.entrySet()) {
            cands.add(new AiAnalyzer.DecompileReq(en.getKey(), en.getValue(),
                    fileClassOfKey(en.getKey(), job.getOldSnap(), job.getNewSnap())));
        }
        if (cands.size() > topK) cands = cands.subList(0, topK);
        return cands;
    }

    /** 全量候选（不截断）：智能分类需覆盖全部变更文件，与 handleClassify 真实调用一致。 */
    private List<AiAnalyzer.DecompileReq> buildAllCandidates(
            Map<String, DecompiledUnit> aiMap, Job job) {
        List<AiAnalyzer.DecompileReq> cands = new ArrayList<>();
        for (Map.Entry<String, DecompiledUnit> en : aiMap.entrySet()) {
            cands.add(new AiAnalyzer.DecompileReq(en.getKey(), en.getValue(),
                    fileClassOfKey(en.getKey(), job.getOldSnap(), job.getNewSnap())));
        }
        return cands;
    }

    private static DiffStatus statusOf(DiffResult diff, String k) {
        for (DiffStatus st : DiffStatus.values()) if (diff.get(st).contains(k)) return st;
        return DiffStatus.MODIFIED;
    }

    /** 归一化 AI 风险等级到 HIGH/MEDIUM/LOW（大小写/中英文兼容，缺省 MEDIUM）。 */
    private static String normalizeRisk(String r) {
        if (r == null) return RISK_MEDIUM;
        String u = r.trim().toUpperCase();
        if (u.contains("HIGH") || u.contains("高")) return "HIGH";
        if (u.contains("LOW") || u.contains("低")) return "LOW";
        return RISK_MEDIUM;
    }

    /** 离线确定性风险启发式（无 API Key 时使用）。 */
    private static String heuristicRisk(String key, FileClass fc) {
        if (fc == FileClass.CONFIG) return "LOW";
        if (fc != null && fc.isFrontendText()) return "LOW";
        String k = (key == null ? "" : key).toLowerCase();
        if (k.contains("controller") || k.contains("api") || k.contains("facade") || k.contains("endpoint")) return "HIGH";
        if (k.contains("dao") || k.contains("mapper") || k.contains("repository")
                || k.contains("service") || k.contains("manager") || k.contains("biz")) return RISK_MEDIUM;
        return RISK_MEDIUM;
    }

    private static String heuristicReason(FileClass fc, DiffStatus st) {
        String status;
        if (st == DiffStatus.ADDED) status = "新增";
        else if (st == DiffStatus.DELETED) status = "删除";
        else status = "修改";
        if (fc == FileClass.CONFIG) return status + "配置文件，影响配置语义/启动加载，建议核对配置项";
        if (fc != null && fc.isFrontendText()) return status + "前端资源，影响页面/交互，建议核对渲染与交互";
        return status + "后端类，按包路径/变更类型初判风险，建议结合上下文复核";
    }

    /** 变更类别启发式（离线确定性，不依赖 LLM 输出）。 */
    private static String categorize(String key, FileClass fc, String intent) {
        if (fc == FileClass.CONFIG) return "配置";
        if (fc == FileClass.JS) return "前端脚本";
        if (fc == FileClass.HTML) return "前端模板";
        if (fc == FileClass.CSS) return "前端样式";
        if (fc == FileClass.JSP) return "页面";
        String k = (key == null ? "" : key).toLowerCase();
        String it = (intent == null ? "" : intent).toLowerCase();
        if (isInterfaceCandidate(k, it)) return "接口";
        if (isDataCandidate(k, it)) return "数据";
        if (isServiceCandidate(k, it)) return "服务";
        if (isConfigCandidate(k)) return "配置";
        if (isModelCandidate(k)) return "模型";
        return "业务代码";
    }

    private static boolean isInterfaceCandidate(String k, String it) {
        return k.contains("controller") || k.contains("api") || k.contains("facade") || it.contains("接口") || it.contains("api");
    }
    private static boolean isDataCandidate(String k, String it) {
        return k.contains("dao") || k.contains("mapper") || k.contains("repository")
                || it.contains("数据库") || it.contains("sql") || it.contains("数据访问");
    }
    private static boolean isServiceCandidate(String k, String it) {
        return k.contains("service") || k.contains("manager") || k.contains("biz") || it.contains("服务");
    }
    private static boolean isConfigCandidate(String k) {
        return k.contains("config") || k.contains("bean") || k.contains("context") || k.contains("properties");
    }
    private static boolean isModelCandidate(String k) {
        return k.contains("entity") || k.contains(AI_MODEL_FIELD) || k.contains("domain")
                || k.contains("dto") || k.contains("vo") || k.contains("po");
    }

    // ---------------- 复用 Main 的映射逻辑 ----------------

    private Map<String, DecompiledUnit> buildClassMap(Job job, int topK) {
        Map<String, DecompiledUnit> m = new LinkedHashMap<>();
        List<String> cands = DiffEngine.collectL1ClassCandidates(job.getResult(), job.getOldSnap(), job.getNewSnap());
        if (cands.size() > topK) cands = cands.subList(0, topK);
        Decompiler dec = new Decompiler(cfrPath(job.opts), findJava());
        com.bempdiff.diff.DiffRules rules = job.opts.toDiffRules();
        for (String k : cands) {
            DecompiledUnit u = dec.decompile(job.getOldSnap(), job.getNewSnap(),
                    resolveOldEntry(job, k), resolveNewEntry(job, k), k, rules);
            // 仅纳入确有实际变更行的类：反编译后无行级差异（如仅字节码/元数据变化）不进 AI，
            // 避免「无差异内容」也被分析（评审：测试要点等专项只应看差异行）。
            // 另：整包被删除/新增的嵌套归档（配对后无同版本键）不逐文件下钻枚举，收敛为容器级，避免噪声。
            if (hasContentChange(u) && !isInnerOfWholeContainerChange(k, job.getResult())) m.put(k, u);
        }
        return m;
    }

    private Map<String, DecompiledUnit> buildTextMap(Job job, int topK) {
        return buildTextMapLimited(job, topK);
    }

    /**
     * 全量文本候选（不截断）：供 AI 阶段B 深读覆盖全部文本类变更文件。
     * 此前 AI 候选复用 buildTextMap 的 topK 截断，导致「测试要点分析」等专项报告
     * 只对前 topK 个文本文件生成逐文件测试要点，其余变更文件被静默丢掉（用户反馈「报告不全」）。
     * 深度改由 stageBTopK 独立控制；报告正文源码章节仍走 topK 截断，二者解耦。
     */
    private Map<String, DecompiledUnit> buildTextMapAll(Job job) {
        return buildTextMapLimited(job, Integer.MAX_VALUE);
    }

    private Map<String, DecompiledUnit> buildTextMapLimited(Job job, int limitCap) {
        Map<String, DecompiledUnit> m = new LinkedHashMap<>();
        FrontendTextDiff ftd = new FrontendTextDiff();
        com.bempdiff.diff.DiffRules rules = job.opts.toDiffRules();
        List<String> cands = DiffEngine.collectTextDiffCandidates(job.getResult(), job.getOldSnap(), job.getNewSnap());
        int limit = Math.min(cands.size(), limitCap);
        for (int i = 0; i < limit; i++) {
            String k = cands.get(i);
            LogicalEntry oe = resolveOldEntry(job, k);
            LogicalEntry ne = resolveNewEntry(job, k);
            FileClass fc = textFileClass(oe, ne);
            DecompiledUnit u = ftd.diff(job.getOldSnap(), job.getNewSnap(), oe, ne, k, fc, rules);
            // 同样的“无差异不进 AI”：文本文件若被忽略规则吞成空 diff（如仅空白/注释变化），
            // 不送入分析，避免 application.properties 这类无差异条目出现在分析里。
            // 整包被删除/新增的嵌套归档不逐文件下钻，收敛为容器级（见 buildClassMap 同款注释）。
            if (hasContentChange(u) && !isInnerOfWholeContainerChange(k, job.getResult())) m.put(k, u);
        }
        return m;
    }

    /** 是否确有实际变更行：unit 正常且解析出的统一 diff 至少含一个新增/删除块（即存在可分析的差异行）。 */
    private static boolean hasContentChange(DecompiledUnit u) {
        return u != null && u.isOk() && u.getDiffText() != null
                && !com.bempdiff.diff.DiffDigest.parse(u.getDiffText()).isEmpty();
    }

    /**
     * 是否某「嵌套归档内部条目」其所属容器被整体 ADDED/DELETED。
     * 命中说明整包是新/删（相应“同名不同版本”若无配对则容器不在 MODIFIED/UNCHANGED），
     * 内部子文件应随容器整体收敛，不逐文件下钻枚举给 AI——避免“整删/整增一个包却打印海量内部差异”。
     */
    private static boolean isInnerOfWholeContainerChange(String key, DiffResult r) {
        int idx = key.indexOf("!/");
        if (idx < 0) return false;
        String container = key.substring(0, idx);
        return r.get(DiffStatus.DELETED).contains(container) || r.get(DiffStatus.ADDED).contains(container);
    }

    private static FileClass textFileClass(LogicalEntry oe, LogicalEntry ne) {
        if (ne != null) return ne.getFileClass();
        if (oe != null) return oe.getFileClass();
        return FileClass.JS;
    }

    private static FileClass fileClassOfKey(String k, PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        LogicalEntry e = oldSnap.getEntries().get(k);
        if (e == null) e = newSnap.getEntries().get(k);
        return (e != null) ? e.getFileClass() : FileClass.CLASS;
    }

    private static String nullToNA(String s) {
        return (s == null || s.isEmpty()) ? "（未知）" : s;
    }
    /** /api/job/{id}/unpack-report：返回两侧解包状态报告（unpackNested 关闭时 enabled=false）。 */
    private void handleUnpackReport(HttpExchange ex, Job job) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put(KEY_JOB_ID, job.id);
        com.bempdiff.unpack.UnpackReport[] reps = job.getUnpackReports();
        resp.put("enabled", reps != null);
        List<Object> list = new ArrayList<>();
        if (reps != null) {
            for (com.bempdiff.unpack.UnpackReport r : reps) {
                list.add(r == null ? null : Json.parseObject(r.toJson()));
            }
        }
        resp.put("reports", list);
        sendJson(ex, 200, resp);
    }

    /** 解包报告/错误日志落盘目录：{user.home}/.bempdiff/logs。 */
    private static Path unpackLogsDir() {
        return Paths.get(System.getProperty(PROP_USER_HOME, ""), DIR_BEMPDIFF, "logs");
    }

    /** 物理解包运行时根目录：{user.home}/.bempdiff/runtime/<jobId>/（生命周期受控，可整体回收）。 */
    private static Path runtimeRoot() {
        return Paths.get(System.getProperty(PROP_USER_HOME, ""), DIR_BEMPDIFF, DIR_RUNTIME);
    }

    /** 启动清理：删除 runtime 残留（上次崩溃/强杀遗留的作业目录）。 */
    private static void purgeRuntimeStale() {
        Path root = runtimeRoot();
        try {
            Files.createDirectories(root);
            try (java.util.stream.Stream<Path> s = Files.list(root)) {
                s.forEach(BempServer::deleteRuntimeChild);
            }
        } catch (Exception ignored) {
            // 启动清残留失败静默：个别目录被占用/无权限不影响本文件清理与后续启动流程
        }
    }

    /** 清理单个 runtime 子目录（递归删除，容错；启动清残留与 Future 通用）。 */
    private static void deleteRuntimeChild(Path child) {
        if (child == null || !Files.exists(child)) return;
        try {
            if (Files.isDirectory(child)) {
                try (java.util.stream.Stream<Path> s = Files.walk(child)) {
                    s.sorted(java.util.Comparator.reverseOrder())
                     // 空 catch：单文件删除失败静默，自底向上逐文件清理不断链
                     .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) { /* 单文件删除失败忽略 */ } });
                }
            } else {
                Files.deleteIfExists(child);
            }
        } catch (Exception ignored) {
            // 目录遍历/删除失败静默：不因单个孤儿目录占用中断整体清残留
        }
    }

    /** 确保当前作业的解包运行时目录存在：.bempdiff/runtime/<jobId>，并登记到 Job 供后续回收。 */
    private Path ensureJobRuntimeDir(Job job) {
        Path dir = job.getRuntimeDir();
        if (dir != null) return dir;
        try {
            Files.createDirectories(runtimeRoot());
            dir = runtimeRoot().resolve(job.id);
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 作业级目录创建失败：回退系统临时目录（可用性优先，不中断比对；残留概率低）
            try {
                dir = Files.createTempDirectory("bempdiff-unpack"); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
            } catch (IOException e2) {
                throw new IllegalStateException("无法创建解包临时目录", e2);
            }
        }
        job.setRuntimeDir(dir);
        return dir;
    }

    /**
     * 构造解包进度回调：把 {@code NestedUnpacker} 的 root 完成数同步为 job 的 unpacking 阶段进度。
     * 回调跑在解包线程池线程上，且大包 root 很多时会高频触发——必须节流：
     * 距上次上报不足 {@code UNPACK_PROGRESS_MIN_MS} 或进度无变化则跳过，避免 350ms 轮询被刷爆；
     * 解包期间 phase 始终保持 unpacking（而非一闪即失），前端才能采到「自动迭代解包中」。
     */
    private NestedUnpacker.UnpackProgress unpackProgress(final Job job, final String sideMsg) {
        final long minGap = 60L;
        final java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong(-minGap);
        final java.util.concurrent.atomic.AtomicInteger lastProg = new java.util.concurrent.atomic.AtomicInteger(-1);
        return (done, total) -> {
            long now = System.currentTimeMillis();
            if (now - last.get() < minGap) return;
            if (total <= 0) return;
            int p = 33 + (int) Math.round(4.0 * done / total); // 33~37% 区间（与解析 10/30、diffing 50+ 区分）
            if (p == lastProg.getAndSet(p)) return;
            // markRunning 为 volatile 字段写，线程安全；并发最后写者胜，进度单调更稳
            job.markRunning(STAGE_UNPACKING, String.format("%s 已解包 %d/%d", sideMsg, done, total), p);
            last.set(now);
        };
    }

    /**
     * 新比对开始：回收其它（已被取代）作业的物理解包运行时目录，使磁盘仅保留当前作业的解包产物。
     * T00426：同时释放历史作业驻留堆内的双侧快照（含 memory-backed 字节并回扣全局 memo 计数）——
     * GUI 单会话远达不到 MAX_JOBS=200 淘汰线，历史 DONE 任务的快照原样驻留会令堆占用随对比次数线性上涨。
     * 保留策略：仅豁免「最近结束的 1 个终态作业」（即当前屏幕上正在展示的上一轮结果，仍可点开文件/反编译），
     * 其余全部释放；当前 RUNNING/QUEUED 作业一律不动。
     */
    private void sweepSupersededJobs(Job current) {
        Job keep = null;
        for (Job j : store.all().values()) {
            if (j == current || j == null) continue;
            boolean finished = !"QUEUED".equals(j.getStatus()) && !"RUNNING".equals(j.getStatus());
            if (finished && (keep == null || j.finishedAtMillis > keep.finishedAtMillis)) keep = j;
        }
        for (Job j : store.all().values()) {
            if (j == current || j == keep) continue;
            j.cleanupRuntime();
            j.releaseMemory();
        }
    }
}

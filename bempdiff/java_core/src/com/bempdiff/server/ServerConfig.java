package com.bempdiff.server;

import com.bempdiff.config.AiConfig;
import com.bempdiff.config.ParseConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Web 服务端配置（持久化到 properties 文件）。
 * 与 javafx_ui 的 UiConfig 语义对齐，但置于 java_core，使 server 子命令自包含（不依赖 UI 模块）。
 * 字段覆盖：AI 连接配置、解析选项、差异树过滤偏好、项目级上下文等。
 */
public final class ServerConfig {
    private static final Logger LOG = Logger.getLogger(ServerConfig.class.getName());
    /** 扩展名/前缀列表的分隔正则（逗号、分号、空白），多处拆分共用。 */
    private static final String LIST_SPLIT_REGEX = "[,;\\s]+";

    // properties 键常量：同一键在 load/save/updateFrom/toJson 中多处复用，集中定义避免字面量重复。
    private static final String K_AI_PROVIDER = "aiProvider";
    private static final String K_AI_BASE_URL = "aiBaseUrl";
    private static final String K_AI_API_KEY = "aiApiKey";
    private static final String K_AI_MODEL = "aiModel";
    private static final String K_AI_ENABLED = "aiEnabled";
    private static final String K_STAGE_B_TOP_K = "stageBTopK";
    private static final String K_STAGE_A_TOP_K = "stageATopK";
    private static final String K_STAGE_A_FILE_SAMPLE_LINES = "stageAFileSampleLines";
    private static final String K_MAX_PROMPT_TOKENS = "maxPromptTokens";
    private static final String K_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String K_COST_GATE_WARN_TOKENS = "costGateWarnTokens";
    private static final String K_INTERNAL_PREFIXES = "internalPrefixes";
    private static final String K_EXPAND_ALL = "expandAll";
    private static final String K_TOP_K = "topK";
    private static final String K_CFR_JAR = "cfrJar";
    private static final String K_IGNORE_EXTENSIONS = "ignoreExtensions";
    private static final String K_HTTP_PROXY = "httpProxy";
    private static final String K_HTTPS_PROXY = "httpsProxy";
    private static final String K_BLOCK_PRIVATE_ENDPOINTS = "blockPrivateEndpoints";
    private static final String K_PERSIST_API_KEY = "persistApiKey";
    private static final String K_GITHUB_TOKEN = "githubToken";
    private static final String K_PERSIST_GITHUB_TOKEN = "persistGithubToken";
    private static final String K_PROJECT_CONTEXT_DIR = "projectContextDir";
    private static final String K_PROJECT_CONTEXT_ENABLED = "projectContextEnabled";
    private static final String K_FILTER_SEARCH = "filterSearch";
    private static final String K_FILTER_REGEX = "filterRegex";
    private static final String K_FILTER_SHOW_MODIFIED = "filterShowModified";
    private static final String K_FILTER_SHOW_ADDED = "filterShowAdded";
    private static final String K_FILTER_SHOW_DELETED = "filterShowDeleted";
    private static final String K_FILTER_SHOW_UNCHANGED = "filterShowUnchanged";
    private static final String K_AUTO_AI_ON_COMPARE = "autoAiOnCompare";
    private static final String K_UNPACK_NESTED = "unpackNested";
    private static final String K_UNPACK_THREADS = "unpackThreads";
    private static final String K_UNPACK_MAX_DEPTH = "unpackMaxDepth";
    private static final String K_FALSE = "false";

    private String aiProvider = "openai";
    private String aiBaseUrl = "https://api.openai.com/v1";
    private String aiApiKey = "";
    private String aiModel = "gpt-4o-mini";
    private boolean aiEnabled = false;
    private int stageBTopK = 15;
    private int stageATopK = 30;               // 阶段A 概览纳入文件上限（默认对齐 AiConfig）
    private int stageAFileSampleLines = 80;    // 阶段A 单文件 diff 摘要行数上限
    private int maxPromptTokens = 120_000;     // 单次请求最大输入 token（模型上下文窗口护栏）
    private int maxOutputTokens = 8192;        // 单次请求最大输出 token；0=不设（请求体不带 max_tokens）
    private double costGateWarnTokens = 8000;

    private String internalPrefixes = "com.hundsun";
    private boolean expandAll = false;
    private int topK = 15;
    private String cfrJar = "";
    // 比对级忽略扩展名（如 .log/.mf/.properties）。逗号分隔落盘；比对时透传给 ParseConfig 解析阶段过滤。
    private java.util.List<String> ignoreExtensions = new java.util.ArrayList<>();
    private String httpProxy = "";
    private String httpsProxy = "";
    private boolean blockPrivateEndpoints = false;
    private boolean persistApiKey = false;
    // GitHub 更新检查访问令牌（可选）：仅私有仓库或规避匿名限流时使用。
    // 语义对齐 aiApiKey：仅在 persistGithubToken=true 时落盘明文；默认不落盘，仅存活于进程内存。
    private String githubToken = "";
    private boolean persistGithubToken = false;

    private String projectContextDir = "";
    private boolean projectContextEnabled = false;

    private String filterSearch = "";
    private boolean filterRegex = false;
    private boolean filterShowModified = true;
    private boolean filterShowAdded = true;
    private boolean filterShowDeleted = true;
    private boolean filterShowUnchanged = true;
    private boolean autoAiOnCompare = true;

    // 自动逐层解包（WAR/ZIP/JAR 嵌套归档多线程物理展开）：默认开启，使嵌套包在比对完成后即自动解包，
    // AI/导出覆盖嵌套子文件；解包在 DONE 之前完成（job 状态门控保证 AI/导出不会在解包中执行）。
    private boolean unpackNested = true;
    private int unpackThreads = 4;
    private int unpackMaxDepth = 6;

    private final Path file;

    // P1-F 异步落盘器：单一后台线程顺序写文件，合并突发 PUT（只落最新快照），请求线程不阻塞于磁盘 IO。
    private final java.util.concurrent.ExecutorService saveExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bempdiff-config-save");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicReference<Properties> pendingSave =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** P1-F：就地更新后异步落盘。每次构建最新快照入队，后台线程循环排空，突发并发写自然合并为最新状态。 */
    private void scheduleAsyncSave() {
        pendingSave.set(toProperties());
        saveExecutor.execute(this::flushPendingSave);
    }
    /** P1-F：排空待写快照直到无新内容（合并突发）；getAndSet 原子取走，写期间到达的新快照被同一循环续写。 */
    private void flushPendingSave() {
        Properties snap;
        while ((snap = pendingSave.getAndSet(null)) != null) {
            writeProperties(snap);
        }
    }

    public ServerConfig(Path file) {
        this.file = file;
        // JVM 退出前把未落盘的最新快照写完（shutdown 后已提交任务仍会执行，循环会排空 pending）。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveExecutor.shutdown();
            try {
                saveExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        load();
    }

    public static ServerConfig defaultLocation() {
        Path dir = Paths.get(System.getProperty("user.home"), ".bempdiff");
        return new ServerConfig(dir.resolve("bempdiff-web.properties"));
    }

    public void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            aiProvider = p.getProperty(K_AI_PROVIDER, aiProvider);
            aiBaseUrl = p.getProperty(K_AI_BASE_URL, aiBaseUrl);
            aiApiKey = p.getProperty(K_AI_API_KEY, aiApiKey);
            aiModel = p.getProperty(K_AI_MODEL, aiModel);
            aiEnabled = Boolean.parseBoolean(p.getProperty(K_AI_ENABLED, K_FALSE));
            stageBTopK = Integer.parseInt(p.getProperty(K_STAGE_B_TOP_K, "15"));
            stageATopK = Math.max(1, Integer.parseInt(p.getProperty(K_STAGE_A_TOP_K, "30")));
            stageAFileSampleLines = Math.max(1, Integer.parseInt(p.getProperty(K_STAGE_A_FILE_SAMPLE_LINES, "80")));
            maxPromptTokens = Math.max(1000, Integer.parseInt(p.getProperty(K_MAX_PROMPT_TOKENS, "120000")));
            maxOutputTokens = Math.max(0, Integer.parseInt(p.getProperty(K_MAX_OUTPUT_TOKENS, "8192")));
            costGateWarnTokens = Double.parseDouble(p.getProperty(K_COST_GATE_WARN_TOKENS, "8000"));
            internalPrefixes = p.getProperty(K_INTERNAL_PREFIXES, internalPrefixes);
            expandAll = Boolean.parseBoolean(p.getProperty(K_EXPAND_ALL, K_FALSE));
            topK = Integer.parseInt(p.getProperty(K_TOP_K, "15"));
            cfrJar = p.getProperty(K_CFR_JAR, "");
            httpProxy = p.getProperty(K_HTTP_PROXY, "");
            httpsProxy = p.getProperty(K_HTTPS_PROXY, "");
            blockPrivateEndpoints = Boolean.parseBoolean(p.getProperty(K_BLOCK_PRIVATE_ENDPOINTS, K_FALSE));
            persistApiKey = Boolean.parseBoolean(p.getProperty(K_PERSIST_API_KEY, K_FALSE));
            projectContextDir = p.getProperty(K_PROJECT_CONTEXT_DIR, "");
            projectContextEnabled = Boolean.parseBoolean(p.getProperty(K_PROJECT_CONTEXT_ENABLED, K_FALSE));
            filterSearch = p.getProperty(K_FILTER_SEARCH, "");
            filterRegex = Boolean.parseBoolean(p.getProperty(K_FILTER_REGEX, K_FALSE));
            filterShowModified = Boolean.parseBoolean(p.getProperty(K_FILTER_SHOW_MODIFIED, "true"));
            filterShowAdded = Boolean.parseBoolean(p.getProperty(K_FILTER_SHOW_ADDED, "true"));
            filterShowDeleted = Boolean.parseBoolean(p.getProperty(K_FILTER_SHOW_DELETED, "true"));
            filterShowUnchanged = Boolean.parseBoolean(p.getProperty(K_FILTER_SHOW_UNCHANGED, "true"));
            autoAiOnCompare = Boolean.parseBoolean(p.getProperty(K_AUTO_AI_ON_COMPARE, "true"));
            unpackNested = Boolean.parseBoolean(p.getProperty(K_UNPACK_NESTED, "true"));
            unpackThreads = Integer.parseInt(p.getProperty(K_UNPACK_THREADS, "4"));
            unpackMaxDepth = Integer.parseInt(p.getProperty(K_UNPACK_MAX_DEPTH, "6"));
            ignoreExtensions = parseExtList(p.getProperty(K_IGNORE_EXTENSIONS, ""));
        } catch (IOException | NumberFormatException e) {
            LOG.log(Level.WARNING, "加载服务端配置失败", e);
        }
    }

    public void save() {
        writeProperties(toProperties());
    }

    /** 把当前内存配置物化为 Properties 快照（供同步 save 与异步合并写共用同一构建逻辑，避免两侧漂移）。 */
    private Properties toProperties() {
        Properties p = new Properties();
        p.setProperty(K_AI_PROVIDER, aiProvider);
        p.setProperty(K_AI_BASE_URL, aiBaseUrl);
        if (persistApiKey && aiApiKey != null && !aiApiKey.isEmpty()) {
            p.setProperty(K_AI_API_KEY, aiApiKey);
        }
        // 必须持久化 persistApiKey 开关本身：否则重启后 load() 读到默认值 false，
        // 勾选「记住 API Key」的状态丢失（复选框不显示已保存状态）。
        p.setProperty(K_PERSIST_API_KEY, String.valueOf(persistApiKey));
        // 必须持久化 persistGithubToken 开关本身（否则重启后丢失勾选状态）；令牌仅在开启时落盘。
        p.setProperty(K_PERSIST_GITHUB_TOKEN, String.valueOf(persistGithubToken));
        if (persistGithubToken && githubToken != null && !githubToken.isEmpty()) {
            p.setProperty(K_GITHUB_TOKEN, githubToken);
        }
        p.setProperty(K_AI_MODEL, aiModel);
        p.setProperty(K_AI_ENABLED, String.valueOf(aiEnabled));
        p.setProperty(K_STAGE_B_TOP_K, String.valueOf(stageBTopK));
        p.setProperty(K_STAGE_A_TOP_K, String.valueOf(stageATopK));
        p.setProperty(K_STAGE_A_FILE_SAMPLE_LINES, String.valueOf(stageAFileSampleLines));
        p.setProperty(K_MAX_PROMPT_TOKENS, String.valueOf(maxPromptTokens));
        p.setProperty(K_MAX_OUTPUT_TOKENS, String.valueOf(maxOutputTokens));
        p.setProperty(K_COST_GATE_WARN_TOKENS, String.valueOf(costGateWarnTokens));
        p.setProperty(K_INTERNAL_PREFIXES, internalPrefixes);
        p.setProperty(K_EXPAND_ALL, String.valueOf(expandAll));
        p.setProperty(K_TOP_K, String.valueOf(topK));
        p.setProperty(K_CFR_JAR, cfrJar == null ? "" : cfrJar);
        p.setProperty(K_HTTP_PROXY, httpProxy);
        p.setProperty(K_HTTPS_PROXY, httpsProxy);
        p.setProperty(K_BLOCK_PRIVATE_ENDPOINTS, String.valueOf(blockPrivateEndpoints));
        p.setProperty(K_PROJECT_CONTEXT_DIR, projectContextDir == null ? "" : projectContextDir);
        p.setProperty(K_PROJECT_CONTEXT_ENABLED, String.valueOf(projectContextEnabled));
        p.setProperty(K_FILTER_SEARCH, filterSearch == null ? "" : filterSearch);
        p.setProperty(K_FILTER_REGEX, String.valueOf(filterRegex));
        p.setProperty(K_FILTER_SHOW_MODIFIED, String.valueOf(filterShowModified));
        p.setProperty(K_FILTER_SHOW_ADDED, String.valueOf(filterShowAdded));
        p.setProperty(K_FILTER_SHOW_DELETED, String.valueOf(filterShowDeleted));
        p.setProperty(K_FILTER_SHOW_UNCHANGED, String.valueOf(filterShowUnchanged));
        p.setProperty(K_AUTO_AI_ON_COMPARE, String.valueOf(autoAiOnCompare));
        p.setProperty(K_UNPACK_NESTED, String.valueOf(unpackNested));
        p.setProperty(K_UNPACK_THREADS, String.valueOf(unpackThreads));
        p.setProperty(K_UNPACK_MAX_DEPTH, String.valueOf(unpackMaxDepth));
        p.setProperty(K_IGNORE_EXTENSIONS, joinExtList(ignoreExtensions));
        return p;
    }

    private void writeProperties(Properties p) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "创建配置目录失败", e);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "BEMP Web Server Config (可能含 API Key，请勿提交/共享)");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "保存服务端配置失败", e);
        }
    }

    /** 转成核心 ParseConfig。 */
    public ParseConfig toParseConfig() {
        ParseConfig c = new ParseConfig();
        c.setInternalPrefixes(java.util.Arrays.asList(internalPrefixes.split(LIST_SPLIT_REGEX)));
        c.setExpandInternalLib(expandAll);
        c.setMaxEntryBytes(8L * 1024 * 1024);
        return c;
    }

    /** 转成核心 AiConfig。 */
    public AiConfig toAiConfig() {
        AiConfig c = new AiConfig();
        c.setEnabled(aiEnabled);
        c.setProvider(aiProvider);
        c.setBaseUrl(aiBaseUrl);
        c.setApiKey(aiApiKey);
        c.setModel(aiModel);
        c.setStageBTopK(stageBTopK);
        c.setStageATopK(stageATopK);
        c.setStageAFileSampleLines(stageAFileSampleLines);
        c.setMaxPromptTokens(maxPromptTokens);
        c.setMaxOutputTokens(maxOutputTokens);
        c.setCostGateWarnTokens(costGateWarnTokens);
        c.setHttpProxy(httpProxy);
        c.setHttpsProxy(httpsProxy);
        c.setBlockPrivateEndpoints(blockPrivateEndpoints);
        return c;
    }

    /** 项目上下文目录（智能分类/AI 分析注入项目画像用）。 */
    public String getProjectContextDir() { return projectContextDir; }
    /** 是否启用项目上下文画像。 */
    public boolean isProjectContextEnabled() { return projectContextEnabled; }

    /** GitHub 更新检查访问令牌（配置中心可填；为空则由 UpdateCheckService 回落环境变量 GITHUB_TOKEN）。 */
    public String getGithubToken() { return githubToken; }

    /** 序列化为 JSON（GET 不回显 apiKey 除非持久化开启）。 */
    public java.util.Map<String, Object> toJson() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(K_AI_PROVIDER, aiProvider);
        m.put(K_AI_BASE_URL, aiBaseUrl);
        m.put(K_AI_MODEL, aiModel);
        m.put(K_AI_ENABLED, aiEnabled);
        m.put(K_STAGE_B_TOP_K, stageBTopK);
        m.put(K_STAGE_A_TOP_K, stageATopK);
        m.put(K_STAGE_A_FILE_SAMPLE_LINES, stageAFileSampleLines);
        m.put(K_MAX_PROMPT_TOKENS, maxPromptTokens);
        m.put(K_MAX_OUTPUT_TOKENS, maxOutputTokens);
        m.put(K_COST_GATE_WARN_TOKENS, costGateWarnTokens);
        m.put(K_INTERNAL_PREFIXES, internalPrefixes);
        m.put(K_EXPAND_ALL, expandAll);
        m.put(K_TOP_K, topK);
        m.put(K_CFR_JAR, cfrJar);
        m.put(K_HTTP_PROXY, httpProxy);
        m.put(K_HTTPS_PROXY, httpsProxy);
        m.put(K_BLOCK_PRIVATE_ENDPOINTS, blockPrivateEndpoints);
        m.put(K_PERSIST_API_KEY, persistApiKey);
        m.put(K_PERSIST_GITHUB_TOKEN, persistGithubToken);
        m.put(K_PROJECT_CONTEXT_DIR, projectContextDir);
        m.put(K_PROJECT_CONTEXT_ENABLED, projectContextEnabled);
        m.put(K_FILTER_SEARCH, filterSearch);
        m.put(K_FILTER_REGEX, filterRegex);
        m.put(K_FILTER_SHOW_MODIFIED, filterShowModified);
        m.put(K_FILTER_SHOW_ADDED, filterShowAdded);
        m.put(K_FILTER_SHOW_DELETED, filterShowDeleted);
        m.put(K_FILTER_SHOW_UNCHANGED, filterShowUnchanged);
        m.put(K_AUTO_AI_ON_COMPARE, autoAiOnCompare);
        m.put(K_UNPACK_NESTED, unpackNested);
        m.put(K_UNPACK_THREADS, unpackThreads);
        m.put(K_UNPACK_MAX_DEPTH, unpackMaxDepth);
        m.put(K_IGNORE_EXTENSIONS, new java.util.ArrayList<>(ignoreExtensions));
        // 仅在用户开启「记住 API Key」时回显明文 Key：此时密钥本就落盘（明文存于 properties），
        // 回显到前端不增加额外暴露；未开启（默认安全模式）则只给 hasApiKey 标记，避免把内存态密钥泄露到 UI。
        m.put("hasApiKey", aiApiKey != null && !aiApiKey.isEmpty());
        if (persistApiKey && aiApiKey != null && !aiApiKey.isEmpty()) {
            m.put(K_AI_API_KEY, aiApiKey);
        }
        return m;
    }

    /** 从 JSON 更新（PUT），并就地保存（同步落盘；保确定性，供测试/需要立即可见落盘的场景）。 */
    public void updateFrom(java.util.Map<String, Object> m) {
        applyAll(m);
        save();
    }

    /** P1-F：从 JSON 就地更新 + 异步合并落盘（HTTP PUT 使用）。
     *  磁盘写委托给单一后台线程并合并突发（只落最新快照），请求线程不阻塞于 IO，
     *  使 config-put 延迟从 ~90-178ms 降到内存级（性能报告 §9-F）。 */
    public void updateFromAsync(java.util.Map<String, Object> m) {
        applyAll(m);
        scheduleAsyncSave();
    }

    private void applyAll(java.util.Map<String, Object> m) {
        applyAiFields(m);
        applyParseAndNetworkFields(m);
        applyProjectAndFilterFields(m);
    }

    private void applyAiFields(java.util.Map<String, Object> m) {
        if (m.containsKey(K_AI_PROVIDER)) aiProvider = Json.str(m, K_AI_PROVIDER, aiProvider);
        if (m.containsKey(K_AI_BASE_URL)) aiBaseUrl = Json.str(m, K_AI_BASE_URL, aiBaseUrl);
        if (m.containsKey(K_AI_MODEL)) aiModel = Json.str(m, K_AI_MODEL, aiModel);
        if (m.containsKey(K_AI_ENABLED)) aiEnabled = Json.bool(m, K_AI_ENABLED, aiEnabled);
        if (m.containsKey(K_STAGE_B_TOP_K)) stageBTopK = Json.intv(m, K_STAGE_B_TOP_K, stageBTopK);
        if (m.containsKey(K_STAGE_A_TOP_K)) stageATopK = Math.max(1, Json.intv(m, K_STAGE_A_TOP_K, stageATopK));
        if (m.containsKey(K_STAGE_A_FILE_SAMPLE_LINES)) stageAFileSampleLines = Math.max(1, Json.intv(m, K_STAGE_A_FILE_SAMPLE_LINES, stageAFileSampleLines));
        if (m.containsKey(K_MAX_PROMPT_TOKENS)) maxPromptTokens = Math.max(1000, Json.intv(m, K_MAX_PROMPT_TOKENS, maxPromptTokens));
        if (m.containsKey(K_MAX_OUTPUT_TOKENS)) maxOutputTokens = Math.max(0, Json.intv(m, K_MAX_OUTPUT_TOKENS, maxOutputTokens));
        if (m.containsKey(K_COST_GATE_WARN_TOKENS)) costGateWarnTokens = Json.intv(m, K_COST_GATE_WARN_TOKENS, (int) costGateWarnTokens);
        if (m.containsKey(K_AI_API_KEY)) {
            String k = Json.str(m, K_AI_API_KEY, "");
            if (k != null && !k.isEmpty()) aiApiKey = k;
        }
    }

    private void applyParseAndNetworkFields(java.util.Map<String, Object> m) {
        if (m.containsKey(K_INTERNAL_PREFIXES)) internalPrefixes = Json.str(m, K_INTERNAL_PREFIXES, internalPrefixes);
        if (m.containsKey(K_EXPAND_ALL)) expandAll = Json.bool(m, K_EXPAND_ALL, expandAll);
        if (m.containsKey(K_TOP_K)) topK = Json.intv(m, K_TOP_K, topK);
        if (m.containsKey(K_CFR_JAR)) cfrJar = Json.str(m, K_CFR_JAR, cfrJar);
        if (m.containsKey(K_HTTP_PROXY)) httpProxy = Json.str(m, K_HTTP_PROXY, httpProxy);
        if (m.containsKey(K_HTTPS_PROXY)) httpsProxy = Json.str(m, K_HTTPS_PROXY, httpsProxy);
        if (m.containsKey(K_BLOCK_PRIVATE_ENDPOINTS)) blockPrivateEndpoints = Json.bool(m, K_BLOCK_PRIVATE_ENDPOINTS, blockPrivateEndpoints);
        if (m.containsKey(K_PERSIST_API_KEY)) persistApiKey = Json.bool(m, K_PERSIST_API_KEY, persistApiKey);
        if (m.containsKey(K_UNPACK_NESTED)) unpackNested = Json.bool(m, K_UNPACK_NESTED, unpackNested);
        if (m.containsKey(K_UNPACK_THREADS)) unpackThreads = Math.max(1, Json.intv(m, K_UNPACK_THREADS, unpackThreads));
        if (m.containsKey(K_UNPACK_MAX_DEPTH)) unpackMaxDepth = Math.max(1, Json.intv(m, K_UNPACK_MAX_DEPTH, unpackMaxDepth));
    }

    private void applyProjectAndFilterFields(java.util.Map<String, Object> m) {
        if (m.containsKey(K_PROJECT_CONTEXT_DIR)) projectContextDir = Json.str(m, K_PROJECT_CONTEXT_DIR, projectContextDir);
        if (m.containsKey(K_PROJECT_CONTEXT_ENABLED)) projectContextEnabled = Json.bool(m, K_PROJECT_CONTEXT_ENABLED, projectContextEnabled);
        if (m.containsKey(K_FILTER_SEARCH)) filterSearch = Json.str(m, K_FILTER_SEARCH, filterSearch);
        if (m.containsKey(K_FILTER_REGEX)) filterRegex = Json.bool(m, K_FILTER_REGEX, filterRegex);
        if (m.containsKey(K_FILTER_SHOW_MODIFIED)) filterShowModified = Json.bool(m, K_FILTER_SHOW_MODIFIED, filterShowModified);
        if (m.containsKey(K_FILTER_SHOW_ADDED)) filterShowAdded = Json.bool(m, K_FILTER_SHOW_ADDED, filterShowAdded);
        if (m.containsKey(K_FILTER_SHOW_DELETED)) filterShowDeleted = Json.bool(m, K_FILTER_SHOW_DELETED, filterShowDeleted);
        if (m.containsKey(K_FILTER_SHOW_UNCHANGED)) filterShowUnchanged = Json.bool(m, K_FILTER_SHOW_UNCHANGED, filterShowUnchanged);
        if (m.containsKey(K_AUTO_AI_ON_COMPARE)) autoAiOnCompare = Json.bool(m, K_AUTO_AI_ON_COMPARE, autoAiOnCompare);
        if (m.containsKey(K_IGNORE_EXTENSIONS)) {
            ignoreExtensions = extractExtensionList(m.get(K_IGNORE_EXTENSIONS));
        }
    }

    /** 扩展名配置转列表（兼容 List 与逗号分隔字符串两种形态；与 parseExtList 同规）。 */
    private static java.util.List<String> extractExtensionList(Object ig) {
        java.util.List<String> exts = new java.util.ArrayList<>();
        if (ig instanceof java.util.List) {
            for (Object x : (java.util.List<?>) ig) {
                if (x != null && !String.valueOf(x).trim().isEmpty()) exts.add(String.valueOf(x).trim());
            }
        } else if (ig != null) { // 兼容逗号分隔的字符串形态
            for (String s : String.valueOf(ig).split(LIST_SPLIT_REGEX)) {
                if (!s.trim().isEmpty()) exts.add(s.trim());
            }
        }
        return exts;
    }

    // 扩展名列表 <-> 逗号分隔字符串 互转（持久化用；与 updateFrom 的字符串兼容分支呼应）
    private static java.util.List<String> parseExtList(String joined) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (joined == null || joined.isEmpty()) return out;
        for (String s : joined.split(LIST_SPLIT_REGEX)) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }
    private static String joinExtList(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return String.join(",", list);
    }
}
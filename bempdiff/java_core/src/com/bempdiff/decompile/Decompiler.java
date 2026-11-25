package com.bempdiff.decompile;

import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.diff.DiffRules;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.OutputSinkFactory.Sink; // NOSONAR(S1128) - 用于 getSink 返回类型，CFR 嵌套 API 类型误报未被识别
import org.benf.cfr.reader.api.OutputSinkFactory.SinkClass; // NOSONAR(S1128) - getSupportedSinks 签名使用
import org.benf.cfr.reader.api.OutputSinkFactory.SinkType; // NOSONAR(S1128) - getSupportedSinks/getSink 参数使用
import org.benf.cfr.reader.api.SinkReturns.Decompiled;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 反编译集成（T07/T08，§5.3 / §5.4）。对应 prototype: decompile.py
 *  - CFR（生产首选）优先；失败降级 javap（JDK 自带，签名级）
 *  - GBK 容错解码：CFR 在有中文常量的 class 上按系统编码输出 stdout（prototype 已踩坑，Java 侧同形）
 *  - 对 L1 修改/新增/删除类做双栏源码 diff（统一格式，供双栏视图 / AI 深读）
 *
 * 性能修复（P0-1，见性能测试报告 §6）：
 *  - 旧实现对每个 class 用 `new ProcessBuilder(java -jar cfr.jar).waitFor()` 起**全新 JVM 子进程**，
 *    逐类冷启动 ~1.4s/类 + 每子进程重读大 jar → 并发 I/O/CPU 争用、亚线性扩展（实测 T16 无收益、外部并发 3.8× 退化）。
 *  - 新实现：当 cfr.jar 位于运行时 classpath 上时，改用 **CFR 进程内 API（CfrDriver）**，纯进程内反编译、无子进程、无冷启动；
 *    由调用方线程池并行驱动即可线性扩展。运行时若检测不到 CfrDriver（cfr.jar 未上 classpath），
 *    自动回退到原 ProcessBuilder 方案，保证真实产品行为零回归。
 */
public final class Decompiler {

    private final Path cfrJar;   // 可能为 null（仅用 javap / 或走进程内 CFR）
    private final String javaBin;

    /** cfr.jar 在运行时 classpath 上、进程内 CFR API 可用时为 true；否则回退 ProcessBuilder。 */
    private final boolean inProcessCfrAvailable;

    private static final String JAVAP = "javap";
    /** NF-02 安全 V1：反编译子进程硬超时。旧实现 p.waitFor() 无上限，畸形 class/CFR 死循环会永久挂住请求线程。
     *  默认 20s，可用 -Dbempdiff.decompileTimeoutSeconds=5..120 调整。 */
    private static final int DECOMPILE_TIMEOUT_SECONDS = resolveTimeoutSeconds();
    /** 缺失侧哈希占位串（7 个零），与 DecompiledUnit 的占位语义一致。 */
    private static final String MISSING_HASH = "0000000";

    private static int resolveTimeoutSeconds() {
        try {
            int v = Integer.parseInt(System.getProperty("bempdiff.decompileTimeoutSeconds", "20"));
            return Math.max(5, Math.min(120, v));
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    /** P1-1 反编译缓存：sha256(class bytes) → 源码，access-order LRU max 256，避免重复 CFR。
     *  P0-A（性能测试报告 §8#1/§9-A）升级为 static「进程级 LRU」：
     *  反编译结果仅由 class 字节决定，是内容寻址、确定性的，整个 JVM 内共享绝对安全；
     *  BempServer 中 Decompiler 实例多为短命（computeUnitByKey/handleClassify 每请求 new 一个，
     *  单类用完即丢），实例级缓存跨调用零命中、跨 job 只能落到磁盘——static 后同一 class 字节
     *  在任意 Decompiler 实例、任意 job 之间命中同一份内存结果，跨实例复用不再依赖磁盘 IO。 */
    private static final int MAX_DECOMPILE_CACHE = 256;
    private static final Map<String, String> decompileCache =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_DECOMPILE_CACHE;
                }
            });

    /** P1-D 反编译全局并发闸门：限制同时进行的反编译计算数量，防高并发下 CPU/内存峰值（性能报告 §8#4）。
     *  大小取 CPU 核数——进程内 CFR 为 CPU 密集；javap 子进程路径亦受此钳制（进程内 P0-A 已消除 cfr 子进程，
     *  但降级子进程仍可能随请求膨胀）。缓存命中的短路径不消耗许可，故绝大多数读多场景不受影响。 */
    private static final Semaphore DECOMPILE_GATE =
            new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors()));

    /** NF-02：进程内 CFR 无法从外部强杀，放到守护线程里执行并由 Future 限时等待；
     *  超时后请求线程立即返回（外层降级 javap），遗留线程由 daemon 属性保证不阻塞进程退出。
     *  H-05：固定线程池 + 有界队列，超时后遗留的 CFR 线程最多占满池，不会像 cachedThreadPool
     *  那样无限创建线程；队列满时直接拒绝并降级 javap，避免请求堆积。 */
    private static final int CFR_POOL_SIZE = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService INPROC_CFR_POOL =
            new ThreadPoolExecutor(
                    CFR_POOL_SIZE, CFR_POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(Math.max(8, CFR_POOL_SIZE * 2)),
                    r -> {
                        Thread t = new Thread(r, "bempdiff-cfr-inproc");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    /**
     * D5 跨会话持久化反编译缓存（2026-08-17，来源 docs/BempDiff_AI优化与竞品分析报告.md P0）。
     * 在内存 LRU 之上叠加一层内容寻址磁盘缓存：
     *  - 缓存键即 class 字节的 sha256，跨 Decompiler 实例、跨进程重启天然共享（同一份字节永远命中）。
     *  - 内存未命中先查磁盘，命中则回填内存 LRU；新反编译结果异步落盘（single-thread 守护执行器，best-effort）。
     *  - 磁盘软上限 DISK_CACHE_MAX，超限按文件 mtime 升序淘汰最旧条目，避免无限膨胀。
     *  - version 标记：缓存目录内置 version.txt，版本不符（如 CFR 升级）启动时整体清空，杜绝旧格式残留。
     *  - 磁盘目录不可用（无写权限等）时静默降级为纯内存，不影响主流程。
     */
    private static final int DISK_CACHE_MAX = 4000;
    private static final int CACHE_VERSION = 1;
    /** M-05：单线程 + 有界队列，磁盘写不及时丢弃缓存任务（best-effort），避免无界积压拖垮内存。 */
    private static final ExecutorService diskWriter =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "bempdiff-diskcache");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.DiscardPolicy());
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            diskWriter.shutdown();
            try { diskWriter.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }));
    }
    private static final AtomicLong diskCount = new AtomicLong(0);
    private static final AtomicLong diskHits = new AtomicLong(0);
    private static volatile Path diskCacheDir = null; // NOSONAR(S3077) - Path 引用不可变，volatile 保证可见性即可

    private static Path resolveCacheDir() {
        if (diskCacheDir != null) return diskCacheDir; // 可能为 null（已降级）
        synchronized (Decompiler.class) {
            if (diskCacheDir != null) return diskCacheDir;
            Path base;
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isEmpty()) {
                base = Paths.get(local, "bempdiff", "decompile-cache");
            } else {
                base = Paths.get(System.getProperty("user.home", "."), ".bempdiff", "decompile-cache");
            }
            try {
                Files.createDirectories(base);
                wipeIfVersionMismatch(base);
                try (Stream<Path> s = Files.list(base)) {
                    diskCount.set(s.filter(p -> p.getFileName().toString().endsWith(".dec")).count());
                }
            } catch (IOException e) {
                base = null; // 降级为纯内存
            }
            diskCacheDir = base;
            return base;
        }
    }

    private static void wipeIfVersionMismatch(Path dir) {
        Path vf = dir.resolve("version.txt");
        try {
            if (Files.exists(vf)) {
                String v = new String(Files.readAllBytes(vf), StandardCharsets.UTF_8).trim();
                if (!String.valueOf(CACHE_VERSION).equals(v)) {
                    try (Stream<Path> s = Files.list(dir)) {
                        s.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { /* 忽略 */ } });
                    }
                }
            }
            Files.write(vf, String.valueOf(CACHE_VERSION).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 版本文件不可写：失去版本保护，但命中仍可用
        }
    }

    private static String diskGet(String key) {
        Path dir = resolveCacheDir();
        if (dir == null) return null;
        Path f = dir.resolve(key + ".dec");
        if (!Files.exists(f)) return null;
        try {
            if (Files.size(f) <= 0) { // M-05：空/半截缓存不能回填内存
                Files.deleteIfExists(f);
                return null;
            }
            diskHits.incrementAndGet();
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void diskPutAsync(String key, String value) {
        Path dir = resolveCacheDir();
        if (dir == null) return;
        diskWriter.submit(() -> {
            Path f = dir.resolve(key + ".dec");
            Path tmp = null;
            try {
                // M-05：同目录临时文件 + 原子 move，崩溃只可能留下 .tmp，不会出现半截 .dec。
                tmp = Files.createTempFile(dir, "dec-", ".tmp");
                Files.write(tmp, value.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                long c = diskCount.incrementAndGet();
                if (c > DISK_CACHE_MAX) evictDisk(dir, DISK_CACHE_MAX * 8 / 10);
            } catch (IOException | RejectedExecutionException ignored) {
                // 落盘失败/队列满：best-effort，忽略
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* 忽略 */ }
                }
            }
        });
    }

    private static void evictDisk(Path dir, long keepBelow) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s
                    .filter(p -> p.getFileName().toString().endsWith(".dec"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .collect(Collectors.toList());
            long toRemove = files.size() - keepBelow;
            for (int i = 0; i < toRemove && i < files.size(); i++) {
                try { Files.deleteIfExists(files.get(i)); diskCount.decrementAndGet(); } // NOSONAR(S1141) - try-with-resources 内嵌删除，不拆分
                catch (IOException ignored) { /* 忽略 */ }
            }
        } catch (IOException ignored) { /* 忽略目录枚举失败 */ }
    }

    /** 磁盘缓存命中计数（自 JVM 启动），供状态栏/日志诊断，可选调用。 */
    public static long getDiskHits() { return diskHits.get(); }

    public Decompiler(Path cfrJar, String javaBin) {
        this.cfrJar = cfrJar;
        this.javaBin = javaBin;
        boolean avail;
        try {
            Class.forName("org.benf.cfr.reader.api.CfrDriver", false,
                    Decompiler.class.getClassLoader());
            avail = true;
        } catch (Exception | LinkageError t) {
            avail = false;
        }
        this.inProcessCfrAvailable = avail;
    }

    /** 反编译单个 .class 字节，返回完整源码（prototype: decompile.decompile）。
     *  P1-1：sha256(bytes) 命中缓存则直接返回，否则 CFR 并写入缓存。 */
    private String decompileOne(byte[] classBytes) throws IOException {
        String cacheKey = sha256Hex(classBytes);
        // 1) 内存 LRU 命中
        String cached = decompileCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // 2) 跨会话磁盘命中（内容寻址）→ 回填内存 LRU
        String diskCached = diskGet(cacheKey);
        if (diskCached != null) {
            decompileCache.put(cacheKey, diskCached);
            return diskCached;
        }

        // P1-D：仅在真正计算（CFR/javap）前取闸门许可，缓存命中路径不消耗许可。
        // acquireUninterruptibly 避免 InterruptedException 侵入仅声明 IOException 的签名。
        // H-05：先成功创建临时文件再取许可，避免 createTempFile 失败时漏 release；deleteOnExit 兜底。
        File tmp = File.createTempFile("bempdiff-cls-", ".class"); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
        tmp.deleteOnExit();
        DECOMPILE_GATE.acquireUninterruptibly();
        try {
            Files.write(tmp.toPath(), classBytes);
            String result;
            if (inProcessCfrAvailable) {
                try {
                    result = cfrInProcess(tmp);
                } catch (Exception cfrFail) {
                    result = "// [降级] CFR 进程内失败：" + cfrFail.getMessage() + "；已回退 javap\n" + javap(tmp);
                }
            } else if (cfrJar != null && cfrJar.toFile().isFile()) {
                try {
                    result = cfr(tmp, cfrJar);
                } catch (IOException cfrFail) {
                    result = "// [降级] CFR 失败：" + cfrFail.getMessage() + "；已回退 javap\n" + javap(tmp);
                }
            } else {
                result = javap(tmp);
            }
            decompileCache.put(cacheKey, result);
            // 超时/失败后的 javap 是降级结果，不能写入跨会话磁盘缓存，否则下次会直接命中残缺源码。
            if (!result.startsWith("// [降级]")) diskPutAsync(cacheKey, result);
            return result;
        } finally {
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignored) {
                // Windows 上 CFR 可能仍持有句柄；deleteOnExit 兜底，不能因清理失败覆盖已生成结果或漏 release。
            } finally {
                DECOMPILE_GATE.release();
            }
        }
    }

    /**
     * 进程内 CFR 反编译（P0-1 核心修复）。
     * 用 CfrDriver 直接在宿主 JVM 内反编译，捕获 DECOMPILED 输出，无子进程、无冷启动。
     * 每次调用自建一个 driver 实例（CFR 非线程安全共享实例），由调用方线程池并行驱动以线性扩展。
     */
    private String cfrInProcess(File classFile) throws IOException {
        Future<String> future;
        try {
            future = INPROC_CFR_POOL.submit(() -> cfrInProcessUnbounded(classFile));
        } catch (RejectedExecutionException e) {
            throw new IOException("CFR 线程池繁忙，已降级 javap", e);
        }
        try {
            return future.get(DECOMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new IOException("CFR 进程内反编译超时（>" + DECOMPILE_TIMEOUT_SECONDS + "s），已中断请求线程");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("CFR 进程内反编译失败：" + (cause == null ? ee.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("CFR 进程内反编译被中断", ie);
        }
    }

    private String cfrInProcessUnbounded(File classFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> sinkClasses) {
                if (sinkType == SinkType.JAVA) {
                    return Collections.singletonList(SinkClass.DECOMPILED);
                }
                return Collections.emptyList();
            }

            @Override
            public Sink<Decompiled> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                    return d -> sb.append(d.getJava()); // DECOMPILED 槽投递 SinkReturns.Decompiled（非 String），取 getJava() 得源码
                }
                return null;
            }
        };
        CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(sinkFactory)
                .build();
        driver.analyse(Collections.singletonList(classFile.getAbsolutePath()));
        String out = sb.toString();
        if (out.trim().isEmpty()) {
            throw new IOException("CFR 进程内返回空");
        }
        return out;
    }

    /**
     * NF-02：启动子进程并带硬超时读取合并后的 stdout/stderr。
     * 读取必须放在独立线程——若在主线程先 readAll 再 waitFor，进程挂住时 readAll 先永久阻塞，超时永远轮不到。
     * 超时后 destroyForcibly + 中断读线程，保证请求线程和子进程都能退出。
     */
    private static String runProcessWithTimeout(ProcessBuilder pb, String label) throws IOException {
        Process p = pb.start();
        final StringBuilder out = new StringBuilder();
        final IOException[] readErr = new IOException[1];
        Thread reader = new Thread(() -> {
            try {
                out.append(decode(readAll(p.getInputStream())));
            } catch (IOException e) {
                readErr[0] = e;
            }
        }, "bempdiff-decompile-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = p.waitFor(DECOMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            p.destroyForcibly();
            reader.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException(label + "被中断", ie);
        }
        if (!finished) {
            p.destroyForcibly();
            reader.interrupt();
            throw new IOException(label + "超时（>" + DECOMPILE_TIMEOUT_SECONDS + "s），已强制终止子进程");
        }
        try {
            reader.join(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (reader.isAlive()) {
            p.getInputStream().close();
            reader.interrupt();
        }
        if (readErr[0] != null) throw readErr[0];
        if (p.exitValue() != 0) {
            String text = out.toString().trim();
            throw new IOException(label + "退出码 " + p.exitValue() + (text.isEmpty() ? "" : "：" + text));
        }
        return out.toString();
    }

    private String cfr(File classFile, Path cfrJar) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-jar", cfrJar.toString(), classFile.getAbsolutePath(),
                "--sugarenums", "false", "--hideutf", "false", "--silent", "true");
        // P1-3：stderr 并入 stdout 单流读取，消除双管道缓冲满导致的进程死锁（CFR 子进程）。
        pb.redirectErrorStream(true);
        String out = runProcessWithTimeout(pb, "CFR");
        if (out.trim().isEmpty()) throw new IOException("CFR 返回空");
        return out;
    }

    private String javap(File classFile) throws IOException {
        // 注意：javaBin 指向 java 启动器；javap 是同目录下的独立可执行文件，需另行推导，
        // 不可直接复用 javaBin（否则等价于 `java -p -c`，会报 "-p requires module path specification"）。
        ProcessBuilder pb = new ProcessBuilder(javapBin(), "-p", "-c", classFile.getAbsolutePath());
        // P1-3：stderr 并入 stdout 单流读取，消除双管道死锁（javap 子进程）。
        pb.redirectErrorStream(true);
        String out = runProcessWithTimeout(pb, "javap");
        if (out.trim().isEmpty()) throw new IOException("javap 返回空");
        return "// [降级] javap 签名级反编译（环境无 CFR）\n" + out;
    }

    /** 对单个 key 做双栏源码 diff（默认规则）。
     *  先取出两侧 class 字节，再委托 {@link #decompileBytes(byte[], byte[], String, DiffRules)} 完成反编译与 diff，避免逻辑重复。 */
    public DecompiledUnit decompile(PackageSnapshot oldSnap, PackageSnapshot newSnap,
                                    LogicalEntry oldEntry, LogicalEntry newEntry, String key) {
        return decompile(oldSnap, newSnap, oldEntry, newEntry, key, DiffRules.DEFAULT);
    }

    /** 对单个 key 做双栏源码 diff（P0-②：带忽略规则）。 */
    public DecompiledUnit decompile(PackageSnapshot oldSnap, PackageSnapshot newSnap,
                                    LogicalEntry oldEntry, LogicalEntry newEntry, String key, DiffRules rules) {
        try {
            byte[] oldBytes = (oldEntry != null) ? readBytes(oldSnap, oldEntry) : null;
            byte[] newBytes = (newEntry != null) ? readBytes(newSnap, newEntry) : null;
            return decompileBytes(oldBytes, newBytes, key, rules);
        } catch (IOException e) {
            return DecompiledUnit.fail(key, e.getMessage());
        } catch (Exception e) {
            return DecompiledUnit.fail(key, e.toString());
        }
    }

    /** 直接对原始 class 字节做反编译并双栏源码 diff（供 LibJarDiff 对 lib jar 内部 class 使用；默认规则）。 */
    public DecompiledUnit decompileBytes(byte[] oldBytes, byte[] newBytes, String key) {
        return decompileBytes(oldBytes, newBytes, key, DiffRules.DEFAULT);
    }

    /** 直接对原始 class 字节做反编译并双栏源码 diff（带忽略规则）。
     *  任一側字节为 null 表示该侧不存在（新增类/删除类），与 decompile() 语义一致。 */
    public DecompiledUnit decompileBytes(byte[] oldBytes, byte[] newBytes, String key, DiffRules rules) {
        try {
            String oldSrc = (oldBytes != null) ? decompileOne(oldBytes) : null;
            String newSrc = (newBytes != null) ? decompileOne(newBytes) : null;
            String diff = unifiedDiff(oldSrc, newSrc, rules);
            boolean degraded = (oldSrc != null && oldSrc.startsWith("// [降级]"))
                    || (newSrc != null && newSrc.startsWith("// [降级]"));
            String engine;
            if (degraded) {
                engine = JAVAP;
            } else if (inProcessCfrAvailable) {
                engine = "cfr(in-process)";
            } else {
                engine = "cfr";
            }
            // 短哈希：原字节侧 SHA-1 前 7 hex；缺失侧 0000000
            String oldHash = (oldBytes != null) ? com.bempdiff.util.ShortHash.ofBytes(oldBytes) : MISSING_HASH;
            String newHash = (newBytes != null) ? com.bempdiff.util.ShortHash.ofBytes(newBytes) : MISSING_HASH;
            return new DecompiledUnit(key, oldSrc, newSrc, diff, engine, "", true, oldHash, newHash);
        } catch (IOException e) {
            // 失败时也计算哈希：保留「字节指纹」便于定位，即使反编译失败仍能在 filebar 区分两侧
            return failWithHash(key, oldBytes, newBytes, e.getMessage());
        } catch (Exception e) {
            return failWithHash(key, oldBytes, newBytes, e.toString());
        }
    }

    /** 失败也计算短哈希的统一封装，避免两处 catch 分支重复计算逻辑。 */
    private static DecompiledUnit failWithHash(String key, byte[] oldBytes, byte[] newBytes, String err) {
        String oldHash = (oldBytes != null) ? com.bempdiff.util.ShortHash.ofBytes(oldBytes) : MISSING_HASH;
        String newHash = (newBytes != null) ? com.bempdiff.util.ShortHash.ofBytes(newBytes) : MISSING_HASH;
        return new DecompiledUnit(key, null, null, "", "cfr", err, false, oldHash, newHash);
    }

    private byte[] readBytes(PackageSnapshot snap, LogicalEntry entry) throws IOException {
        // 复用 PackageParser.readEntryBytes（同包下可见性为包级，这里用反射/公开方法均可；
        // 简化：直接复制解析器逻辑调用其 public 方法）
        return new com.bempdiff.parse.PackageParser().readEntryBytes(snap, entry);
    }

    /** 从 java 启动器路径推导同目录的 javap 可执行文件路径（Windows 为 javap.exe）。 */
    private String javapBin() {
        String j = javaBin;
        if (j != null && j.endsWith("java.exe")) {
            return j.substring(0, j.length() - "java.exe".length()) + "javap.exe";
        }
        if (j != null && j.endsWith("java")) {
            return j.substring(0, j.length() - 4) + JAVAP;
        }
        return JAVAP; // 兜底：依赖 PATH
    }

    /** 生成统一格式 diff 文本（P0-②：带忽略规则）。 */
    private static String unifiedDiff(String oldSrc, String newSrc, DiffRules rules) {
        List<String> a = oldSrc == null ? new ArrayList<>() : lines(oldSrc);
        List<String> b = newSrc == null ? new ArrayList<>() : lines(newSrc);
        StringBuilder sb = new StringBuilder();
        if (oldSrc == null) sb.append("// [新增类] 老包无此文件\n");
        if (newSrc == null) sb.append("// [删除类] 新包无此文件（潜在破坏性变更）\n");
        // 简单 LCS 行级 diff（够用，不引入第三方库）
        List<String> diff = simpleDiff(a, b, rules);
        for (String line : diff) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static List<String> lines(String s) {
        return Arrays.asList(s.split("\n", -1));
    }

    /** 轻量行级 diff（LCS），带忽略规则：归一化行用于匹配，输出仍为原始行（P0-②）。 */
    private static List<String> simpleDiff(List<String> a, List<String> b, DiffRules rules) { // NOSONAR(S3776) - LCS 算法递推，分支源于必要监控
        int n = a.size();
        int m = b.size();
        List<String> ak = new ArrayList<>(n);
        List<String> bk = new ArrayList<>(m);
        for (String s : a) ak.add(rules.normalize(s));
        for (String s : b) bk.add(rules.normalize(s));
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = ak.get(i).equals(bk.get(j)) ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (ak.get(i).equals(bk.get(j))) {
                out.add("  " + a.get(i)); // 输出原始行
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add("- " + a.get(i++));
            } else {
                out.add("+ " + b.get(j++));
            }
        }
        while (i < n) out.add("- " + a.get(i++));
        while (j < m) out.add("+ " + b.get(j++));
        return out;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    /** P1-1：sha256 字节数组，返回 hex 字符串（用作反编译缓存键）。 */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** CFR/javap 中文常量按系统编码输出：utf-8 -> gbk -> 容错（prototype: _decode） */
    private static String decode(byte[] bs) {
        for (String enc : new String[]{StandardCharsets.UTF_8.name(), "gbk", "latin-1"}) {
            try {
                return new String(bs, enc);
            } catch (Exception e) {
                // 尝试下一种编码
            }
        }
        try {
            return new String(bs, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return new String(bs); // 最后兜底
        }
    }
}

package com.bempdiff.ai.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 项目级上下文门面服务（需求 4/4）：AI 分析调用方（BempServer.runAiAnalysis / CLI report --ai）
 * 只需调 {@link #resolve(String)}，即可拿到「递归识别 + 每项目理解 + 缓存」后的
 * {@link ProjectIndex}。内部编排 {@link ProjectIndexer}（发现+理解）与 {@link ProjectContextCache}（缓存）。
 *
 * <p><b>调用与注入流程</b>：
 * <ol>
 *   <li>resolve(projDir) → 读缓存；未命中 → {@link ProjectIndexer#scan} 递归识别全部项目并逐个理解；</li>
 *   <li>缓存命中但某项目指纹变化 → 仅重算该项目（增量失效），其余复用；</li>
 *   <li>返回 {@link ProjectIndex}：stageA 注入 {@link ProjectIndex#toPromptSection(int)} 仓库级上下文；
 *       stageB 用 {@link ProjectIndex#locate(String)} 反查文件所属项目，注入该项目上下文（精准）；</li>
 *   <li>refresh(projDir) 强制重扫（配置中心「刷新上下文」按钮 / API 参数）。</li>
 * </ol></p>
 */
public final class ProjectContextService {

    private static final Logger LOG = Logger.getLogger(ProjectContextService.class.getName());
    /** 仓库级上下文注入上限（字符），控制多项目场景的 token 成本。 */
    public static final int PROMPT_MAX_CHARS = 6000;

    /** 上下文索引的时效窗口（毫秒）：窗口内缓存视为新鲜，跳过逐文件指纹遍历直接复用上一次结果。
     *  可按需通过 {@link #setCacheTtlMillis(long)} 调整（例如压测/大规模工程下调大窗口降低指纹开销）。 */
    private static volatile long cacheTtlMillis = 30_000L;
    /** 磁盘缓存保留时长：首次加载上下文服务时按文件 mtime 淘汰过期缓存。 */
    private static final long CACHE_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    /** 按目录串行的首次构建/失效重算请求：虚拟线程并发时避免重复全量扫描。 */
    private static final Map<String, CompletableFuture<ProjectIndex>> IN_FLIGHT_RESOLVES =
            new ConcurrentHashMap<>();
    private static final ExecutorService CONTEXT_SCAN_POOL = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("bempdiff-project-context-", 0).factory());

    static {
        ProjectContextCache.purgeStale(CACHE_RETENTION_MILLIS);
    }

    /** 调整上下文索引时效窗口。窗口为 0/负值时退化为「每请求必查指纹」（更实时、更慢）。 */
    public static void setCacheTtlMillis(long ttlMs) {
        cacheTtlMillis = ttlMs;
    }

    private ProjectContextService() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /** 缓存优先解析；未命中或指纹变化时增量重算并回写缓存。返回 null 表示目录无效。 */
    public static ProjectIndex resolve(String projDir) {
        if (projDir == null || projDir.trim().isEmpty()) return null;
        Path root = Paths.get(projDir.trim());
        if (!Files.isDirectory(root)) return null;
        String resolveKey = root.toAbsolutePath().normalize().toString();
        CompletableFuture<ProjectIndex> pending = IN_FLIGHT_RESOLVES.computeIfAbsent(
                resolveKey, key -> startResolve(projDir));
        try {
            return pending.join();
        } finally {
            IN_FLIGHT_RESOLVES.remove(resolveKey, pending);
        }
    }

    private static CompletableFuture<ProjectIndex> startResolve(String projDir) {
        CompletableFuture<ProjectIndex> future = new CompletableFuture<>();
        CONTEXT_SCAN_POOL.execute(() -> {
            try {
                future.complete(resolveUncached(projDir));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static ProjectIndex resolveUncached(String projDir) {
        Path root = Paths.get(projDir.trim());
        ProjectIndex cached = ProjectContextCache.load(projDir);
        if (cached != null) {
            // 时效窗口内：直接把缓存当新鲜结果返回，避免每次请求对全量工程做指纹遍历（P0 性能缺陷：大工程每请求秒级）。
            // 窗口逻辑：上次校验(缓存记录/内存缓存)距今 < TTL 即复用，超出窗口再走逐文件指纹增量失效。
            long staleAt = ProjectContextCache.lastValidatedMillis(projDir, cached);
            if (cacheTtlMillis > 0L && System.currentTimeMillis() - staleAt < cacheTtlMillis) {
                LOG.log(Level.FINE, "[AI] 项目上下文时效窗口内复用：{0}（{1} 个项目）",
                        new Object[]{projDir, cached.size()});
                return cached;
            }
            ProjectIndex fresh = refreshChanged(cached, root);
            if (fresh == null) {
                LOG.log(Level.INFO, "[AI] 项目上下文缓存命中：{0}（{1} 个项目）",
                        new Object[]{projDir, cached.size()});
                ProjectContextCache.markValidated(projDir);
                return cached;
            }
            ProjectContextCache.save(projDir, fresh);
            LOG.log(Level.INFO, "[AI] 项目上下文部分失效重算：{0}（{1} 个项目）",
                    new Object[]{projDir, fresh.size()});
            return fresh;
        }
        long t0 = System.currentTimeMillis();
        ProjectIndex idx = ProjectIndexer.scan(root);
        ProjectContextCache.save(projDir, idx);
        LOG.log(Level.INFO, "[AI] 项目上下文首次构建：{0}（{1} 个项目，{2}ms）",
                new Object[]{projDir, idx.size(), System.currentTimeMillis() - t0});
        return idx;
    }

    /** 强制重建（忽略缓存与指纹），供手动刷新。 */
    public static ProjectIndex refresh(String projDir) {
        ProjectContextCache.clear(projDir);
        return resolve(projDir);
    }

    /** 清空缓存目录（配置中心「清空上下文缓存」）。 */
    public static void clearAll() {
        ProjectContextCache.clearMemory();
        Path dir = ProjectContextCache.cacheDir();
        try (java.util.stream.Stream<Path> st = Files.list(dir)) {
            st.filter(p -> p.getFileName().toString().endsWith(".json"))
              .forEach(p -> {
                  try { Files.deleteIfExists(p); } catch (Exception ignored) { /* 单文件删除失败静默：由外层 catch 兜底 */ }
              });
        } catch (Exception ignored) {
            // 目录不存在或不可读，忽略
        }
    }

    /** 渲染为与旧管线兼容的 ProjectContext（stageA 仓库级注入 / 报告展示）。 */
    public static ProjectContext renderContextView(ProjectIndex idx) {
        if (idx == null || idx.isEmpty()) return null;
        List<String> projectPaths = new ArrayList<>();
        for (ProjectIndex.ProjectEntry e : idx.getProjects()) projectPaths.add(e.getRelPath());
        return new ProjectContext(
                idx.getRootPath(),
                "多项目仓库（" + idx.size() + " 个项目）",
                projectPaths,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                idx.toPromptSection(PROMPT_MAX_CHARS));
    }

    /** 增量失效：重算指纹变化的项目；全部一致返回 null（全命中）。 */
    private static ProjectIndex refreshChanged(ProjectIndex cached, Path root) {
        List<ProjectIndex.ProjectEntry> rebuilt = new ArrayList<>();
        boolean changed = false;
        for (ProjectIndex.ProjectEntry e : cached.getProjects()) {
            Path projectRoot = root.resolve(e.getRelPath().replace('/', java.io.File.separatorChar));
            if (!Files.isDirectory(projectRoot)) {
                // 项目被删除 → 整体重扫
                return ProjectIndexer.scan(root);
            }
            ProjectIndexer.Fingerprint fp = ProjectIndexer.fingerprintOf(projectRoot);
            if (!fp.hash.equals(e.getFingerprint())) {
                changed = true;
                ProjectContext ctx = ProjectContextAnalyzer.analyze(projectRoot);
                rebuilt.add(new ProjectIndex.ProjectEntry(e.getRelPath(), fp.hash, fp.javaFileCount, ctx));
            } else {
                rebuilt.add(e);
            }
        }
        if (!changed) return null;
        return new ProjectIndex(cached.getRootPath(), System.currentTimeMillis(), rebuilt);
    }
}

package com.bempdiff.ai.context;

import com.bempdiff.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 项目级上下文缓存（需求 3/4）：将 {@link ProjectIndex} 落盘到
 * {@code ~/.bempdiff/ai-context-cache/<sha256-prefix(projDir)>.json}，避免每次 AI 分析都全量重扫。
 *
 * <p><b>存储方案</b>：用户级目录（不写业务仓库，避免污染 BEMP5.0DEV）；单文件 JSON，
 * 含 rootPath、scannedAt、项目清单（relPath + fingerprint + 完整理解结果）。
 *
 * <p><b>失效机制</b>：按项目指纹（构建文件内容 hash + java 文件数 + 源码最大 mtime）
 * 比对；指纹变化 → 该项目理解自动重算并回写。另提供 {@link #clear(String)} 手动清空
 * 与 {@link ProjectContextService#refresh(String)} 强制重建。
 */
public final class ProjectContextCache {

    private static final Logger LOG = Logger.getLogger(ProjectContextCache.class.getName());
    private static final String DIR_CACHE = "ai-context-cache";
    private static final String SUFFIX = ".json";

    /** 进程内缓存：避免同目录重复读盘；Map 操作线程安全，目录级构建由 Service 去重。 */
    private static final Map<String, ProjectIndex> MEM = new ConcurrentHashMap<>();

    /** 进程内「上次指纹校验时间」（毫秒）：配合 {@link ProjectContextService} 的时效窗口，
     * 记录某目录最后一次做了逐文件指纹失效检查的时刻，窗口内直接复用缓存免遍历。 */
    private static final Map<String, Long> MEM_VALIDATED = new ConcurrentHashMap<>();

    private ProjectContextCache() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    public static Path cacheDir() {
        return Paths.get(System.getProperty("user.home"), ".bempdiff", DIR_CACHE);
    }

    /** 缓存文件路径 = cacheDir/{SHA-256 前 8 字节}.json。 */
    public static Path cacheFile(String projDir) {
        return cacheDir().resolve(shortHash(norm(projDir)) + SUFFIX);
    }

    /** 读缓存：未命中（无文件/解析失败/空索引）返回 null。 */
    public static ProjectIndex load(String projDir) {
        ProjectIndex mem = MEM.get(norm(projDir));
        if (mem != null) return mem;
        Path f = cacheFile(projDir);
        if (!Files.isRegularFile(f)) return null;
        try {
            String txt = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            ProjectIndex idx = ProjectIndex.fromJson(Json.parseObject(txt));
            if (idx.isEmpty()) return null;
            MEM.put(norm(projDir), idx);
            return idx;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "读取项目上下文缓存失败：{0}", e.getMessage());
            return null;
        }
    }

    /** 写缓存（含内存缓存）；IO 失败仅告警，不影响主流程。 */
    public static void save(String projDir, ProjectIndex idx) {
        if (idx == null || idx.isEmpty()) return;
        MEM.put(norm(projDir), idx);
        MEM_VALIDATED.put(norm(projDir), System.currentTimeMillis());
        try {
            Files.createDirectories(cacheDir());
            Path tmp = cacheFile(projDir).resolveSibling(cacheFile(projDir).getFileName() + ".tmp");
            Files.write(tmp, Json.write(idx.toJsonMap()).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, cacheFile(projDir),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "写入项目上下文缓存失败：{0}", e.getMessage());
        }
    }

    /** 标记某目录已完成一次逐文件指纹失效校验（用于时效窗口刷新起点）。 */
    public static void markValidated(String projDir) {
        MEM_VALIDATED.put(norm(projDir), System.currentTimeMillis());
    }

    /** 上次指纹校验时间（毫秒）；进程内无记录时回退到缓存索引的构建时间。 */
    public static long lastValidatedMillis(String projDir, ProjectIndex cached) {
        Long v = MEM_VALIDATED.get(norm(projDir));
        if (v != null) return v;
        return (cached != null) ? cached.getScannedAt() : 0L;
    }

    /** 清除指定目录缓存（手动刷新时调用；含内存与磁盘）。 */
    public static void clear(String projDir) {
        MEM.remove(norm(projDir));
        MEM_VALIDATED.remove(norm(projDir));
        try {
            Files.deleteIfExists(cacheFile(projDir));
        } catch (IOException ignored) {
            // 删除失败无碍
        }
    }

    /** 清空全部内存缓存（配合磁盘清理做全量重置）。 */
    public static void clearMemory() {
        MEM.clear();
        MEM_VALIDATED.clear();
    }

    /** 按 mtime 淘汰超过保留期的磁盘缓存；服务首次加载时调用一次。 */
    public static void purgeStale(long maxAgeMillis) {
        if (maxAgeMillis <= 0L) return;
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        try (java.util.stream.Stream<Path> st = Files.list(cacheDir())) {
            st.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() >= cutoff) return;
                    String rootPath = str(Json.parseObject(Files.readString(p, StandardCharsets.UTF_8)).get("rootPath"));
                    if (Files.deleteIfExists(p)) {
                        clearMemoryForRoot(rootPath);
                        LOG.log(Level.INFO, "已淘汰过期项目上下文缓存：{0}", p.getFileName());
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "淘汰项目上下文缓存失败：{0}", e.getMessage());
                }
            });
        } catch (Exception ignored) {
            // 缓存目录不存在或不可读时无需清理
        }
    }

    private static void clearMemoryForRoot(String rootPath) {
        if (rootPath == null || rootPath.isEmpty()) return;
        MEM.remove(norm(rootPath));
        MEM_VALIDATED.remove(norm(rootPath));
    }

    private static String norm(String s) {
        return (s == null) ? "" : s.replace('\\', '/').replaceAll("/+$", ""); // NOSONAR java:S5852 — 复核通过：输入有界（单行/文件名/限大小小文件 readIfSmall），模式锚定且实际输入无回溯放大
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String str(Object o) {
        return (o == null) ? "" : String.valueOf(o);
    }

    /** 便捷组装：供 Service 返回缓存命中摘要。 */
    public static Map<String, Object> summaryOf(ProjectIndex idx, boolean fromCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rootPath", idx.getRootPath());
        m.put("projectCount", idx.size());
        m.put("scannedAt", idx.getScannedAt());
        m.put("cacheFile", cacheFile(idx.getRootPath()).toString());
        m.put("fromCache", fromCache);
        return m;
    }
}

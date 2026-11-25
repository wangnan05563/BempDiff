package com.bempdiff.server;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CX-09 比对前预检：在不启动解析/比对作业的前提下，快速回答「能不能比、大概要多久、有没有风险」。
 *
 * <p>设计约束：
 * <ul>
 *   <li>不抛异常给 HTTP 层：所有失败都转成 blockers，保证前端总能拿到可展示结果；</li>
 *   <li>扫描有上限（50,000 条目），大目录/大包不做全量 stat，避免预检本身变成一个新的长任务；</li>
 *   <li>只读：不创建临时文件、不写缓存、不启动线程池。</li>
 * </ul>
 */
public final class PreflightService {

    public static final long WARN_SIZE_BYTES = 200L * 1024 * 1024;   // 200 MB
    public static final int WARN_ENTRIES = 50_000;
    public static final int WARN_MAX_DEPTH = 10;
    public static final int SCAN_ENTRY_CAP = 50_000;

    /** 包级预检结果短缓存：同一包在同一进程内重复点「开始比对」时不再重读 zip 中央目录。
     *  key 绑定绝对路径 + mtime + 文件大小 + 忽略扩展名，包被替换后自动失效。 */
    private static final Map<String, Stats> PACKAGE_STATS_CACHE = new ConcurrentHashMap<>();
    private static final int PACKAGE_CACHE_MAX = 128;

    private PreflightService() {}

    /** 单侧扫描结果。 */
    private static final class Stats {
        boolean exists;
        boolean directory;
        boolean readable;
        boolean archive;
        long compressedBytes;
        long uncompressedBytes;
        long entries;
        int maxDepth;
        long ignored;
        boolean capped;
        String error;
        String path;
    }

    /**
     * 执行预检。
     *
     * @param leftPath 老包/老目录
     * @param rightPath 新包/新目录
     * @param mode folder|package
     * @param ignoreExtensions 比对选项里的忽略扩展名（可空）
     */
    public static Map<String, Object> check(String leftPath, String rightPath, String mode,
                                            List<String> ignoreExtensions) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        boolean folderMode = "folder".equals(mode);

        Path left = parsePath(leftPath);
        Path right = parsePath(rightPath);
        if (left == null) blockers.add("老包路径为空或不合法");
        if (right == null) blockers.add("新包路径为空或不合法");
        if (left != null && right != null && samePath(left, right)) {
            blockers.add("老包与新包指向同一路径；同一输入的 0 差异不能证明两个版本一致");
        }

        List<String> ignores = normalizeIgnores(ignoreExtensions);
        Stats ls = left == null ? missing("老包") : inspect(left, folderMode, ignores, "老包");
        Stats rs = right == null ? missing("新包") : inspect(right, folderMode, ignores, "新包");
        collectBlockers(ls, blockers);
        collectBlockers(rs, blockers);
        collectWarnings(ls, warnings, infos);
        collectWarnings(rs, warnings, infos);

        long size = Math.max(ls.uncompressedBytes, ls.compressedBytes) + Math.max(rs.uncompressedBytes, rs.compressedBytes);
        long entries = ls.entries + rs.entries;
        int estimatedSeconds = estimateSeconds(size, entries);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", blockers.isEmpty());
        resp.put("level", blockers.isEmpty() ? (warnings.isEmpty() ? "info" : "warn") : "block");
        resp.put("blockers", blockers);
        resp.put("warnings", warnings);
        resp.put("infos", infos);
        resp.put("estimatedSeconds", estimatedSeconds);
        resp.put("left", toMap(ls));
        resp.put("right", toMap(rs));
        resp.put("checkedAt", System.currentTimeMillis());
        return resp;
    }

    private static Path parsePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Paths.get(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean samePath(Path a, Path b) {
        try {
            // M-08：优先按文件系统同一性判断，能拦住两个指向同一文件的符号链接/硬链接。
            return Files.isSameFile(a, b);
        } catch (IOException | RuntimeException e) {
            try {
                return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private static Stats missing(String label) {
        Stats s = new Stats();
        s.error = label + "路径为空或不合法";
        s.path = "";
        return s;
    }

    private static List<String> normalizeIgnores(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String x : raw) {
            if (x == null) continue;
            String s = x.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) out.add(s.startsWith(".") ? s : "." + s);
        }
        return out;
    }

    private static boolean ignored(String name, List<String> ignores) {
        if (ignores.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ignores) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static Stats inspect(Path p, boolean folderMode, List<String> ignores, String label) {
        String cacheKey = folderMode ? null : packageCacheKey(p, ignores);
        if (cacheKey != null) {
            Stats hit = PACKAGE_STATS_CACHE.get(cacheKey);
            if (hit != null) return hit;
        }
        Stats s = new Stats();
        s.path = p.toString();
        s.exists = Files.exists(p);
        if (!s.exists) {
            s.error = label + "路径不存在：" + p;
            return s;
        }
        s.directory = Files.isDirectory(p);
        s.readable = Files.isReadable(p);
        if (!s.readable) {
            s.error = label + "路径不可读：" + p;
            return s;
        }
        if (folderMode) {
            if (!s.directory) {
                s.error = label + "不是目录（当前为目录对比模式）";
                return s;
            }
            inspectFolder(p, s, ignores);
        } else {
            if (s.directory) {
                s.error = label + "是目录（当前为包对比模式）";
                return s;
            }
            inspectArchive(p, s, ignores);
        }
        if (cacheKey != null && s.error == null) {
            if (PACKAGE_STATS_CACHE.size() > PACKAGE_CACHE_MAX) PACKAGE_STATS_CACHE.clear();
            PACKAGE_STATS_CACHE.put(cacheKey, s);
        }
        return s;
    }

    private static String packageCacheKey(Path p, List<String> ignores) {
        try {
            Path abs = p.toAbsolutePath().normalize();
            long mtime = Files.getLastModifiedTime(abs).toMillis();
            long size = Files.size(abs);
            return abs + "|" + mtime + "|" + size + "|" + String.join(",", ignores);
        } catch (IOException | RuntimeException e) {
            return null; // 无法 stat（不存在/权限/竞态）时退回实时检查，缓存失败不影响预检
        }
    }

    private static void inspectFolder(Path root, Stats s, List<String> ignores) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // M-08：目录也计入 50k 上限，防止“海量空目录”绕过文件计数导致预检卡死。
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    if (s.entries >= SCAN_ENTRY_CAP) {
                        s.capped = true;
                        return FileVisitResult.TERMINATE;
                    }
                    s.entries++;
                    int depth = root.relativize(dir).getNameCount();
                    if (depth > s.maxDepth) s.maxDepth = depth;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (s.entries >= SCAN_ENTRY_CAP) {
                        s.capped = true;
                        return FileVisitResult.TERMINATE;
                    }
                    s.entries++;
                    s.uncompressedBytes += attrs.size();
                    int depth = root.relativize(file).getNameCount();
                    if (depth > s.maxDepth) s.maxDepth = depth;
                    if (ignored(file.getFileName().toString(), ignores)) s.ignored++;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    s.error = "目录扫描失败：" + file + "（" + exc.getMessage() + "）";
                    return FileVisitResult.TERMINATE;
                }
            });
            s.archive = true;
        } catch (IOException | RuntimeException e) {
            s.error = "目录扫描失败：" + e.getMessage();
        }
    }

    private static void inspectArchive(Path p, Stats s, List<String> ignores) {
        try {
            s.compressedBytes = Files.size(p);
        } catch (IOException e) {
            s.error = "读取文件大小失败：" + e.getMessage();
            return;
        }
        try (ZipFile zf = new ZipFile(p.toFile())) {
            s.archive = true;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                if (s.entries >= SCAN_ENTRY_CAP) {
                    s.capped = true;
                    break;
                }
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                s.entries++;
                long sz = e.getSize();
                if (sz > 0) s.uncompressedBytes += sz;
                String name = e.getName() == null ? "" : e.getName();
                int depth = 0;
                for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/') depth++;
                if (depth > s.maxDepth) s.maxDepth = depth;
                if (ignored(name, ignores)) s.ignored++;
            }
        } catch (IOException | RuntimeException e) {
            s.archive = false;
            s.error = "不是可识别的 zip/jar/war 包，或压缩包已损坏：" + e.getMessage();
        }
    }

    private static void collectBlockers(Stats s, List<String> blockers) {
        if (s.error != null) blockers.add(s.error);
    }

    private static void collectWarnings(Stats s, List<String> warnings, List<String> infos) {
        if (s.error != null) return;
        long displaySize = Math.max(s.uncompressedBytes, s.compressedBytes);
        if (displaySize > WARN_SIZE_BYTES) {
            warnings.add(String.format(Locale.ROOT, "%s 约 %.0f MB，超过 200 MB；解析/比对可能较慢，建议先确认是否为目标版本",
                    s.path, displaySize / 1024.0 / 1024.0));
        }
        if (s.entries > WARN_ENTRIES || s.capped) {
            warnings.add(String.format(Locale.ROOT, "%s 条目数达到 %s，已触发预检扫描上限；将依赖树虚拟滚动与解析护栏",
                    s.path, s.capped ? "50,000+" : String.valueOf(s.entries)));
        }
        if (s.maxDepth > WARN_MAX_DEPTH) {
            warnings.add(String.format(Locale.ROOT, "%s 目录/条目层级 %d 层，超过 %d 层；请确认路径是否误选",
                    s.path, s.maxDepth, WARN_MAX_DEPTH));
        }
        if (s.compressedBytes > 0 && s.uncompressedBytes > 0) {
            double ratio = (double) s.uncompressedBytes / s.compressedBytes;
            if (ratio > 100 && s.compressedBytes > 1_000_000L) {
                warnings.add(String.format(Locale.ROOT, "%s 解压后约为压缩包的 %.0f 倍，存在解压炸弹风险，已按安全上限处理",
                        s.path, ratio));
            }
        }
        if (s.ignored > 0) infos.add(s.path + " 命中忽略扩展名 " + s.ignored + " 个，将不参与比对");
    }

    private static int estimateSeconds(long bytes, long entries) {
        double bySize = bytes / 1024.0 / 1024.0 / 20.0;   // 约 20 MB/s 的解析+摘要基线
        double byEntries = entries / 2000.0;             // 树构建/摘要条目开销
        return Math.max(1, (int) Math.ceil(Math.max(bySize, byEntries)));
    }

    private static Map<String, Object> toMap(Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", s.path);
        m.put("exists", s.exists);
        m.put("directory", s.directory);
        m.put("readable", s.readable);
        m.put("archive", s.archive);
        m.put("compressedBytes", s.compressedBytes);
        m.put("uncompressedBytes", s.uncompressedBytes);
        m.put("entries", s.entries);
        m.put("maxDepth", s.maxDepth);
        m.put("ignored", s.ignored);
        m.put("capped", s.capped);
        if (s.error != null) m.put("error", s.error);
        return m;
    }
}

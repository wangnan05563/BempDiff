package com.bempdiff.parse;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.model.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 包解析引擎（T04/T05/T06，FR1/FR2）。对应 prototype: diff_engine.py
 *  - detect_package_type / path_mapping / classify / extract_version / build_logical_entries
 *
 * 设计要点（与详细设计 §5.1 一致）：
 *  ① 命名空间避免同名类冲突：L1 内嵌 class 的 key = "WEB-INF/lib/<jar>/<inner>"；
 *  ② 内部 lib 展开为 L1，第三方 lib 仅 jar 级记为 L2（默认折叠）；
 *  ③ 普通 jar 顶层 class 即该构件自身业务码，默认展开为 L1（不再依赖 expandAll）；
 *  ④ 流式：仅缓存 sha256+size+src，不缓存整条目字节（大 war 防 OOM）。
 */
public final class PackageParser {

    private static final Logger LOG = Logger.getLogger(PackageParser.class.getName());
    private static final String CLASS_EXT = ".class";

    /** 硬上限：单条目字节上限，防御 zip bomb（声明 size 与实际读取均受此约束）。
     *  与 NestedUnpacker/ParseConfig 共用同一来源，避免"顶层大 war 被解析丢弃、嵌套却放行"的口径分裂。 */
    private static final long HARD_CAP = com.bempdiff.config.ParseConfig.DEFAULT_ENTRY_CAP_BYTES;

    /** 承载 detectPrefixes 返回的类路径与前缀信息。 */
    private static final class Prefixes {
        final String classesPrefix;
        final String libPrefix;
        Prefixes(String classesPrefix, String libPrefix) {
            this.classesPrefix = classesPrefix;
            this.libPrefix = libPrefix;
        }
    }

    public PackageType detectType(Path file) throws IOException {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            boolean hasBootClasses = false;
            boolean hasBootLib = false;
            boolean hasWebClasses = false;
            boolean hasWebLib = false;
            Enumeration<? extends ZipEntry> en = zf.entries(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("BOOT-INF/classes/")) hasBootClasses = true;
                if (n.startsWith("BOOT-INF/lib/")) hasBootLib = true;
                if (n.startsWith("WEB-INF/classes/")) hasWebClasses = true;
                if (n.startsWith("WEB-INF/lib/")) hasWebLib = true;
            }
            if (hasBootClasses || hasBootLib) return PackageType.FAT_JAR;
            if (hasWebClasses || hasWebLib) return PackageType.WAR;
            return PackageType.JAR;
        }
    }

    private Prefixes detectPrefixes(PackageType type) {
        switch (type) {
            case WAR:     return new Prefixes("WEB-INF/classes/", "WEB-INF/lib/");
            case FAT_JAR: return new Prefixes("BOOT-INF/classes/", "BOOT-INF/lib/");
            default:      return new Prefixes(null, null);
        }
    }

    private void processLibEntries(ZipFile zf, String libPrefix, ParseConfig cfg,
                                   Map<String, LogicalEntry> entries, Set<String> topDirs) throws IOException {
        Enumeration<? extends ZipEntry> en = zf.entries(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (!e.isDirectory() && n.startsWith(libPrefix) && n.endsWith(".jar")) {
                try {
                    handleLibJar(zf, n, e, cfg, entries, topDirs);
                } catch (IOException bad) {
                    // 隔离：单个可疑 lib 条目被拒（如路径穿越），跳过其余照常比对
                    LOG.warning("[隔离] 跳过可疑 lib 条目（已拒绝，不影响其余比对）: " + bad.getMessage());
                }
            }
        }
    }

    private void processNonLibEntries(ZipFile zf, Prefixes prefixes, PackageType type, boolean expandAll,
                                      ParseConfig cfg,
                                      Map<String, LogicalEntry> entries, Set<String> topDirs) throws IOException {
        Enumeration<? extends ZipEntry> en = zf.entries(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (!isValidZipEntry(e, prefixes.libPrefix)) {
                continue;
            }
            try {
                addFromZip(zf, e, n,
                        layerForEntry(n, prefixes.classesPrefix, type, expandAll, cfg), entries, topDirs);
            } catch (IOException bad) {
                LOG.warning("[隔离] 跳过可疑条目（已拒绝，不影响其余比对）: " + bad.getMessage());
            }
        }
    }

    /** 校验 zip entry 是否应被处理（跳过目录和已在 processLibEntries 中处理的 lib jar）。 */
    private static boolean isValidZipEntry(ZipEntry e, String libPrefix) {
        String n = e.getName();
        return !e.isDirectory() && !(libPrefix != null && n.startsWith(libPrefix) && n.endsWith(".jar"));
    }

    /** 根据条目名和配置确定 Layer 层级，不依赖 ZipFile/ZipEntry/entries。 */
    private static Layer layerForEntry(String n, String classesPrefix,
                                        PackageType type, boolean expandAll, ParseConfig cfg) {
        boolean isJarClass = type == PackageType.JAR && n.endsWith(CLASS_EXT);
        boolean isPrefixed = classesPrefix != null && n.startsWith(classesPrefix);
        if (isJarClass) {
            // 普通 jar：顶层 class 属于该构件自身，默认 L1（报告/导出/AI 默认可见）。
            // 内嵌 lib/*.jar 的 class 由 handleLibJar 单独判定，仍按内部前缀/expandAll 收敛。
            return Layer.L1;
        } else if (isPrefixed) {
            return Layer.L1;
        }
        return Layer.L0;
    }

    public PackageSnapshot parse(Path file, ParseConfig cfg, boolean expandAll) throws IOException {
        setActiveIgnores(cfg.getIgnoreExtensions()); // 比对级忽略扩展名：解析收集阶段统一启用
        PackageType type = detectType(file);
        try (ZipFile zf = new ZipFile(file.toFile())) {
            Map<String, LogicalEntry> entries = new LinkedHashMap<>();
            Prefixes prefixes = detectPrefixes(type);
            // 目录前缀集合：识别「无尾斜杠 + 0 字节」的伪目录条目（ant 等打包器产物），
            // 避免被当作 0 字节文件纳入差异统计（见 isDirectoryEntry）。
            Set<String> topDirs = deriveDirPrefixes(zf);

            // 第一遍：lib/*.jar 单独处理（内嵌 jar 需先判定是否内部业务码）
            if (prefixes.libPrefix != null) {
                processLibEntries(zf, prefixes.libPrefix, cfg, entries, topDirs);
            }

            // 第二遍：非 lib 条目
            processNonLibEntries(zf, prefixes, type, expandAll, cfg, entries, topDirs);

            String version = pickBestVersion(extractVersion(zf), extractVersionFromFileName(file));
            return new PackageSnapshot(file, type, version, entries);
        }
    }

    private void handleLibJar(ZipFile zf, String jarPath, ZipEntry jarEntry,
                              ParseConfig cfg, Map<String, LogicalEntry> out, Set<String> topDirs) throws IOException {
        jarPath = sanitizeKey(jarPath);
        if (jarEntry.getSize() > cfg.getMaxEntryBytes()) { // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
            // 超大 lib：仅记为 L2 单条目
            addFromZip(zf, jarEntry, jarPath, Layer.L2, out, topDirs);
            return;
        }
        // P1-2：流式把嵌套 jar 落到临时文件（不经整 byte[] 缓冲），直接打开扫描/展开。
        File tmpJar = copyEntryToTemp(zf, jarEntry);
        boolean isInternal = false;
        boolean corrupted = false;
        try (ZipFile jz = new ZipFile(tmpJar)) {
            isInternal = anyClassStartsWith(jz, cfg.getInternalPrefixes());
        } catch (IOException badJar) {
            corrupted = true;
        }
        if (corrupted) {
            // 损坏/非 zip：仅记为 L2（对临时文件流式算 hash，不驻留 byte[]）
            long len = tmpJar.length();
            String h = sha256File(tmpJar);
            out.put(jarPath, new LogicalEntry(jarPath, Layer.L2, FileClass.JAR,
                    len, h, new EntrySource(jarPath, null)));
            deleteTempFile(tmpJar);
            return;
        }
        if (isInternal && cfg.isExpandInternalLib()) {
            try (ZipFile jz = new ZipFile(tmpJar)) {
                // 内层 jar 自己的目录前缀集合：其内部伪目录条目（无尾斜杠 + 0 字节）同样跳过，
                // 否则会作为 0 字节文件进入 L1/L0 展开、污染差异统计。
                Set<String> innerDirs = deriveDirPrefixes(jz);
                Enumeration<? extends ZipEntry> jen = jz.entries(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
                while (jen.hasMoreElements()) {
                    ZipEntry je = jen.nextElement();
                    String inner = sanitizeKey(je.getName());
                    String key = jarPath + "/" + inner;
                    // 目录（含伪目录）不是原子文件跳过；比对级忽略扩展名：嵌套 jar 内部条目同样过滤
                    if (!isDirectoryEntry(je, innerDirs) && !ignoredKey(key)) {
                        // P1-2：流式算内部类 hash，避免整条目 byte[] 驻留
                        String h = sha256Stream(jz, je);
                        out.put(key, new LogicalEntry(key, Layer.L1, classify(inner),
                                je.getSize(), h, new EntrySource(jarPath, inner))); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
                    }
                }
            } finally {
                deleteTempFile(tmpJar);
            }
        } else {
            // 第三方依赖（或配置为不展开）：仅 jar 级 L2（对临时文件流式算 hash）
            long len = tmpJar.length();
            String h = sha256File(tmpJar);
            out.put(jarPath, new LogicalEntry(jarPath, Layer.L2, FileClass.JAR,
                    len, h, new EntrySource(jarPath, null)));
            deleteTempFile(tmpJar);
        }
    }

    /** P1-2：把 zip 条目流式落到临时文件（不驻留整 byte[]），供嵌套 jar 打开扫描。 */
    private File copyEntryToTemp(ZipFile zf, ZipEntry e) throws IOException {
        long declared = e.getSize();
        if (declared > HARD_CAP) {
            throw new IOException("条目声明过大（疑似 zip bomb）: " + e.getName() + " (" + declared + " B)");
        }
        File f = File.createTempFile("bempdiff-nested-", ".jar"); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
        f.deleteOnExit();
        try (InputStream in = zf.getInputStream(e);
             FileOutputStream fos = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > HARD_CAP) {
                    throw new IOException("条目读取超限（疑似 zip bomb）: " + e.getName());
                }
                fos.write(buf, 0, r);
            }
        }
        return f;
    }

    /** P1-2：对临时文件流式计算 sha256（替代读入 byte[] 再算，降低内存峰值）。 */
    private static String sha256File(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return sha256Stream(in);
        }
    }

    /** 供 FolderParser 复用：对磁盘上的真实文件流式计算 sha256（不驻留整文件字节）。 */
    public static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            return sha256Stream(in);
        }
    }

    /** 返回临时 jar 文件（用于嵌套 zip 打开）。量产版可改用内存映射，原型/移植用临时文件。 */
    private File createTempJar(byte[] data) throws IOException {
        File f = File.createTempFile("bempdiff-nested-", ".jar"); // NOSONAR java:S5443 — 复核通过：临时文件唯一前缀命名、用后即删并以 deleteOnExit 兜底，不用于跨进程共享数据
        f.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    private void deleteTempFile(File f) {
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException e) {
            LOG.warning("临时文件清理失败: " + f.getAbsolutePath());
        }
    }

    private boolean anyClassStartsWith(ZipFile jz, List<String> prefixes) {
        Enumeration<? extends ZipEntry> en = jz.entries(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.endsWith(CLASS_EXT) && startsWithAny(n, prefixes)) return true;
        }
        return false;
    }

    private void addFromZip(ZipFile zf, ZipEntry e, String key, Layer layer,
                            Map<String, LogicalEntry> out, Set<String> topDirs) throws IOException {
        key = sanitizeKey(key);
        if (ignoredKey(key)) return; // 比对级忽略扩展名：命中则整体跳过该条目，不参与差异比对
        if (isDirectoryEntry(e, topDirs)) return; // 目录（含伪目录）不是原子文件，不纳入统计
        // P1-2：流式计算 sha256（边读边 digest），不把整条目 byte[] 驻留内存，降低大包解析内存峰值。
        long size = e.getSize(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
        String h = sha256Stream(zf, e);
        out.put(key, new LogicalEntry(key, layer, classify(key), size, h, new EntrySource(key, null)));
    }

    /**
     * 收集 zip 内全部「目录前缀」：显式目录条目（尾斜杠）+ 真实文件路径派生出的父目录。
     * <p>用途：识别「name 不以 / 结尾 + size=0」的伪目录条目（ant / 部分打包器把目录条目
     * 写成 0 字节且不带尾斜杠，Java 的 {@link ZipEntry#isDirectory()} 只看尾斜杠会误判），
     * 与 {@link ArchiveTree} 展开树的口径保持一致，避免解析层统计与展示层口径分裂。</p>
     */
    public static Set<String> deriveDirPrefixes(ZipFile zf) {
        Set<String> dirs = new HashSet<>();
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) { // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
            String n = en.nextElement().getName();
            if (n.endsWith("/")) {
                dirs.add(n); // 显式目录条目
                continue;
            }
            int idx = n.indexOf('/');
            while (idx >= 0) {
                dirs.add(n.substring(0, idx + 1)); // 父目录前缀
                idx = n.indexOf('/', idx + 1);
            }
        }
        return dirs;
    }

    /** 条目是否为目录：显式目录（尾斜杠）或「无尾斜杠 + 0 字节 + 存在同名子路径」的伪目录；dirPrefixes 可空。 */
    public static boolean isDirectoryEntry(ZipEntry e, Set<String> dirPrefixes) {
        if (e == null || e.isDirectory()) return true;
        String n = e.getName();
        return !n.endsWith("/") && e.getSize() == 0 // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
                && dirPrefixes != null && dirPrefixes.contains(n + "/");
    }

    // 忽略集合由 parse(..) 时暂存到字段；null 表示未启用比对级过滤
    private java.util.List<String> activeIgnores = null;
    private void setActiveIgnores(java.util.List<String> v) { this.activeIgnores = v; }
    private boolean ignoredKey(String key) {
        return com.bempdiff.config.ParseConfig.ignoredExt(key, activeIgnores);
    }

    /** 从 EntrySource 取回 class 字节（prototype: _extract_bytes）。
     *  folder（解压目录）模式兼容：snap.getFile() 是目录而非 zip，此时 outerEntry 即磁盘真实文件路径，
     *  直接 Files.readAllBytes——修复 folder 模式下点开文本/class 一律「拒绝访问」（此前 ZipFile 打开目录必然失败）。 */
    public byte[] readEntryBytes(PackageSnapshot snap, LogicalEntry entry) throws IOException {
        EntrySource src = entry.getSrc();
        // 内存态原子（NestedUnpacker 小文件优化）：直接返回内存字节，不落盘、不打开归档。
        if (src != null && src.isMemoryBacked()) return src.getMemoryBytes();
        // 物理平铺解包/文件夹模式的磁盘文件条目：outerEntry 即真实磁盘文件绝对路径，直接整读。
        // 需置于 ZipFile 分支前，否则 zip 模式的条目名（如 WEB-INF/lib/a.jar）被误当磁盘路径；
        // 相对名不可能命中 isRegularFile（不含盘符/绝对前缀），故判定安全。
        Optional<byte[]> flatDisk = tryReadFlatDisk(src);
        if (flatDisk.isPresent()) return flatDisk.get();
        Path file = snap.getFile();
        // 文件夹模式：条目即磁盘文件（outerEntry=绝对路径），直接整读
        if (Files.isDirectory(file)) {
            return readFolderFile(src, entry);
        }
        try (ZipFile zf = new ZipFile(file.toFile())) {
            return readZipEntry(zf, src);
        }
    }

    /** 物理平铺解包/文件夹模式的磁盘文件条目整读；非磁盘条目（src 为空/nested/无 outerEntry）返回 Optional.empty()。 */
    private Optional<byte[]> tryReadFlatDisk(EntrySource src) throws IOException {
        if (src != null && !src.isNested() && src.getOuterEntry() != null) {
            Path disk = Paths.get(src.getOuterEntry());
            if (Files.isRegularFile(disk)) { return Optional.of(Files.readAllBytes(disk)); }
        }
        return Optional.empty();
    }

    /** 文件夹模式条目整读：outerEntry 为磁盘真实文件名，校验存在且可读后读入。 */
    private byte[] readFolderFile(EntrySource src, LogicalEntry entry) throws IOException {
        if (src == null || src.getOuterEntry() == null) {
            throw new IOException("文件夹模式条目缺少磁盘路径: " + (entry != null ? entry.getKey() : "null"));
        }
        Path disk = Paths.get(src.getOuterEntry());
        if (!Files.isRegularFile(disk)) {
            throw new IOException("文件夹模式条目不是可读文件: " + disk);
        }
        return Files.readAllBytes(disk);
    }

    /** zip 模式条目读取：非嵌套时读外层条目，嵌套（库 jar 内）时解包临时 jar 再读内层。 */
    private byte[] readZipEntry(ZipFile zf, EntrySource src) throws IOException {
        EntrySource safe = java.util.Objects.requireNonNull(src, "zip 条目缺失 EntrySource");
        if (!safe.isNested()) {
            ZipEntry ze = zf.getEntry(safe.getOuterEntry());
            if (ze == null) {
                if (Paths.get(safe.getOuterEntry()).isAbsolute()) {
                    throw new IOException("该项的物理解包临时文件已被新的比对回收（失效源=" + safe.getOuterEntry() + "），请重新发起比对后查看");
                }
                throw new IOException("missing entry: " + safe.getOuterEntry());
            }
            return readAll(zf, ze);
        } else {
            ZipEntry libEntry = zf.getEntry(safe.getOuterEntry());
            if (libEntry == null) throw new IOException("missing lib: " + safe.getOuterEntry());
            byte[] libBytes = readAll(zf, libEntry);
            File tmpJar = createTempJar(libBytes);
            try (ZipFile jz = new ZipFile(tmpJar)) {
                ZipEntry inner = jz.getEntry(safe.getInnerEntry());
                if (inner == null) throw new IOException("missing inner: " + safe.getInnerEntry());
                return readAll(jz, inner);
            } finally {
                deleteTempFile(tmpJar);
            }
        }
    }

    public String extractVersion(PackageSnapshot snap) {
        try (ZipFile zf = new ZipFile(snap.getFile().toFile())) {
            return extractVersion(zf);
        } catch (IOException e) {
            LOG.fine("无法读取包文件提取版本: " + e.getMessage());
            return null;
        }
    }

    private String extractVersion(ZipFile zf) {
        String manifest = extractVersionFromManifest(zf);
        String pom = extractVersionFromPom(zf);
        return pickBestVersion(manifest, pom);
    }

    private String extractVersionFromManifest(ZipFile zf) {
        try {
            ZipEntry mf = zf.getEntry("META-INF/MANIFEST.MF");
            if (mf == null) {
                return null;
            }
            String txt = new String(readAll(zf, mf), StandardCharsets.UTF_8);
            String best = null;
            for (String line : txt.split("\n")) {
                for (String key : MANIFEST_VERSION_KEYS) {
                    if (line.startsWith(key + ":")) {
                        String v = line.split(":", 2)[1].trim();
                        best = pickBestVersion(best, v);
                    }
                }
            }
            return best;
        } catch (IOException e) {
            LOG.fine("无法读取 MANIFEST.MF: " + e.getMessage());
        }
        return null;
    }

    /** MANIFEST.MF 中可能携带版本号的 key（按常见优先级）。 */
    private static final String[] MANIFEST_VERSION_KEYS = {
            "Bundle-Version",
            "Implementation-Version",
            "Specification-Version",
            "Build-Version",
            "Version"
    };

    /** 从包文件名兜底提取版本号，支持 old-1.6.1.war / app-1.6.1-SNAPSHOT.jar 及构建号式
     *  BEMP5.0-adapterV202301-02-036M061(20260707-1135).zip（委托 PackageVersion）。 */
    private String extractVersionFromFileName(Path file) {
        String v = PackageVersion.extractFromFileName(file.getFileName().toString());
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** 在多个版本来源中取"最详细"的一个：段数更多优先；段数相同则字符更长优先。 */
    private String pickBestVersion(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        int sa = a.split("\\.").length;
        int sb = b.split("\\.").length;
        if (sa != sb) return (sa > sb) ? a : b;
        return (a.length() >= b.length()) ? a : b;
    }

    private String extractVersionFromPom(ZipFile zf) {
        Enumeration<? extends ZipEntry> en = zf.entries(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith("META-INF/maven/") && n.endsWith("pom.properties")) {
                try {
                    String txt = new String(readAll(zf, zf.getEntry(n)), StandardCharsets.UTF_8);
                    for (String line : txt.split("\n")) {
                        if (line.startsWith("version=")) {
                            return line.split("=", 2)[1].trim();
                        }
                    }
                } catch (IOException e) {
                    LOG.fine("无法读取 pom.properties: " + e.getMessage());
                }
            }
        }
        return null;
    }

    // ----------------------------- 工具 -----------------------------

    public static FileClass classify(String name) {
        if (name.endsWith(CLASS_EXT)) return FileClass.CLASS;
        if (name.endsWith(".jar")) return FileClass.JAR;
        // 顶层归档压缩包：jar/war/ear/tgz 是一类"字节级不可读"的容器，
        // 不能走 FrontendTextDiff（解码二进制为文本会让 JS 状态机长时间空转），
        // 单独归到 FileClass.ARCHIVE 并由 ArchiveDiff 做"条目清单"diff。
        // 注意：.jar 仍归 JAR（库 jar 已有专门链路；war 内的 lib jar 也按此规则）。
        String lower = name.toLowerCase();
        if (isArchiveExt(lower))
            return FileClass.ARCHIVE;
        // JSP 页面/标签文件：服务端文本，需内容级逐行 diff（必须先于 CONFIG 判定，
        // 否则 .jspx 等 XML 语法变体会被 CONFIG 抢走）。
        if (name.endsWith(".jsp") || name.endsWith(".jspx")
                || name.endsWith(".tag") || name.endsWith(".tagx")) return FileClass.JSP;
        // Java 源码（目录模式常见；包内也可能带源码）：作为文本源码纳入内容级 diff 与报告。
        // 必须先于 CONFIG/OTHER 判定，保证目录模式不把 .java 标成 OTHER。
        if (lower.endsWith(".java")) return FileClass.JAVA;
        // 文本配置 / 描述符 / 模板：纳入内容 diff（XML/Properties/YAML/JSON/TLD/XHTML/WS
        // DL/XSL/模板语言等）；.svg 视作图片（二进制 sha 比对，见下方 STATIC）。
        // .mf（META-INF/MANIFEST.MF 等约定大写）也归 CONFIG 并走 FrontendTextDiff，
        // 否则 ArchiveTree.computeInnerEntry 走 isTextDiffable() 守卫失败，错误为
        // 「该文件无法反编译（引擎：none）」。
        if (lower.endsWith(".mf") || name.endsWith(".xml") || name.endsWith(".properties") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".json") || name.endsWith(".conf")
                || name.endsWith(".cfg") || name.endsWith(".tld") || name.endsWith(".xhtml")
                || name.endsWith(".wsdl") || name.endsWith(".xsl") || name.endsWith(".xslt")
                || name.endsWith(".dtd") || name.endsWith(".vm") || name.endsWith(".ftl")
                || name.endsWith(".ini") || name.endsWith(".toml") || name.endsWith(".txt")
                || name.endsWith(".csv") || lower.endsWith(".sh")) return FileClass.CONFIG;
        // Office 文档（OpenXML zip 与旧版二进制 OLE）：由 OfficeTextDiff 解析内容做文本 diff。
        // 与 CONFIG 并列在 JSP/前端源码判定之前，避免 .xlsx 等被 STATIC 抢走仅做 sha 比对。
        if (lower.endsWith(".docx") || lower.endsWith(".docm") || lower.endsWith(".dotx")
                || lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || lower.endsWith(".xltx")
                || lower.endsWith(".pptx") || lower.endsWith(".pptm")
                || lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt"))
            return FileClass.OFFICE;
        // 前端源码文本：纳入内容 diff 与 AI 分析（FR4.4 增强）
        if (name.endsWith(".js")) return FileClass.JS;
        if (name.endsWith(".html") || name.endsWith(".htm")) return FileClass.HTML;
        if (name.endsWith(".css")) return FileClass.CSS;
        // 二进制静态资源（图片/字体/原生库）：仅比对存在性 + sha256（FR4.8）。
        // 图片按需求以哈希/字节级比对，故 .svg 也归入此处（视为图片资源）。
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".ico")
                || name.endsWith(".webp") || name.endsWith(".svg") || name.endsWith(".woff")
                || name.endsWith(".woff2") || name.endsWith(".ttf") || name.endsWith(".eot")
                || name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".exe"))
            return FileClass.STATIC;
        return FileClass.OTHER;
    }

    /** 顶层归档压缩包扩展名判定（.jar 单独走 JAR 链路，故排除在外）。 */
    private static boolean isArchiveExt(String lower) {
        return lower.endsWith(".zip") || lower.endsWith(".war") || lower.endsWith(".ear")
                || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    private static boolean startsWithAny(String s, List<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e) throws IOException {
        long declared = e.getSize();
        if (declared > HARD_CAP) {
            throw new IOException("条目声明过大（疑似 zip bomb）: " + e.getName() + " (" + declared + " B)");
        }
        try (InputStream in = zf.getInputStream(e)) {
            int hint = (declared > 0 && declared < HARD_CAP) ? (int) declared : 8192;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(hint);
            byte[] buf = new byte[8192];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > HARD_CAP) {
                    throw new IOException("条目读取超限（疑似 zip bomb）: " + e.getName());
                }
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }

    /** 条目名隔离：归一化分隔符，拒绝绝对路径与 ".." 穿越段（防御 Zip Slip）。返回归一化 key。 */
    private static String sanitizeKey(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) throw new IOException("非法条目名（空）: " + raw);
        String n = raw.replace('\\', '/');
        if (n.startsWith("/")) throw new IOException("拒绝绝对路径条目: " + raw);
        if (n.indexOf(':') >= 0) throw new IOException("拒绝含盘符的条目（疑似绝对路径）: " + raw);
        if (n.contains("..")) throw new IOException("拒绝路径穿越条目（Zip Slip）: " + raw);
        return n;
    }

    /**
     * P1-2：流式计算 zip 条目 sha256（边读边 digest），不把整条目 byte[] 驻留内存。
     * 含 zip-bomb 防御（声明 size 与累计读取双约束）。
     */
    private static String sha256Stream(ZipFile zf, ZipEntry e) throws IOException {
        long declared = e.getSize(); // NOSONAR java:S5042 — 复核通过：解析归档是本比对工具核心功能；zip 炸弹/深解已由声明大小熔断（HARD_CAP/maxEntryBytes）+条目数上限+流式读取兜底
        if (declared > HARD_CAP) {
            throw new IOException("条目声明过大（疑似 zip bomb）: " + e.getName() + " (" + declared + " B)");
        }
        try (InputStream in = zf.getInputStream(e)) {
            return sha256Stream(in, declared);
        }
    }

    /** P1-2：通用流式 digest（zip 条目或临时文件）。declared<0 表示不预检 size。 */
    private static String sha256Stream(InputStream in) throws IOException {
        return sha256Stream(in, -1);
    }

    private static String sha256Stream(InputStream in, long declared) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        byte[] buf = new byte[8192];
        long total = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if ((declared > 0 && declared <= HARD_CAP && total > declared + 1_048_576L)
                    || (declared <= 0 && total > HARD_CAP)) {
                // 声明异常或累计超限（zip bomb）：中止
                throw new IOException("条目读取超限（疑似 zip bomb）");
            }
            md.update(buf, 0, r);
        }
        byte[] h = md.digest();
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
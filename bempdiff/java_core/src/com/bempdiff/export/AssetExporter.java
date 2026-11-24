package com.bempdiff.export;

import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.Layer;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.parse.PackageParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 差异资产导出（T13，FR6）。对应需求 §FR6 / 详细设计 §5.6。
 *  - exportDiffClasses：复制 ADDED/DELETED/MODIFIED 的 class 字节（保持命名空间）
 *  - exportDiffJars：复制变化的 lib/*.jar（L2 变化）
 *  - exportDecompiledSources：Top-K 完整源码 + manifest 清单（zip）
 */
public final class AssetExporter {

    private final PackageParser parser = new PackageParser();

    /** ① 差异 class 目录：仅复制变化的 class（保持命名空间路径），返回输出目录。 */
    public Path exportDiffClasses(DiffResult r, PackageSnapshot oldSnap, PackageSnapshot newSnap,
                                  Path outDir) throws IOException {
        Path classesDir = outDir.resolve("diff-classes");
        Files.createDirectories(classesDir);
        for (DiffStatus st : new DiffStatus[]{DiffStatus.ADDED, DiffStatus.DELETED, DiffStatus.MODIFIED}) {
            for (String k : r.get(st)) {
                exportSingleClass(k, st, oldSnap, newSnap, classesDir);
            }
        }
        return classesDir;
    }

    private void exportSingleClass(String k, DiffStatus st, PackageSnapshot oldSnap,
                                   PackageSnapshot newSnap, Path classesDir) throws IOException {
        LogicalEntry oe = oldSnap.getEntries().get(k);
        LogicalEntry ne = newSnap.getEntries().get(k);
        if (!isClassEntry(oe) && !isClassEntry(ne)) {
            return;
        }
        // 删除类用老包；其余用新包（新增/修改）
        LogicalEntry srcEntry = (st == DiffStatus.DELETED) ? oe : ne;
        PackageSnapshot srcSnap = (st == DiffStatus.DELETED) ? oldSnap : newSnap;
        if (srcEntry == null || srcEntry.getFileClass() == FileClass.FOLDER) {
            return;
        }
        byte[] bytes = parser.readEntryBytes(srcSnap, srcEntry);
        // 越界防护（双保险）：即使解析层漏过恶意条目名，写盘前也要确认落在 diff-classes/ 内
        Path target = classesDir.resolve(k).normalize();
        Path classesDirNorm = classesDir.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(classesDirNorm)) {
            throw new IOException("拒绝越界写文件（Zip Slip）: " + k);
        }
        Files.createDirectories(target.getParent());
        Path statusFile = target.getParent().resolve("." + st.name() + "." + target.getFileName());
        atomicWrite(target, bytes);
        // 标记状态（避免同名覆盖歧义，供人工核对）
        atomicWrite(statusFile, (st.name() + " " + k).getBytes(StandardCharsets.UTF_8));
    }

    /** ② 差异 jar 目录：仅复制变化的 lib/*.jar（L2 变化）。 */
    public Path exportDiffJars(DiffResult r, PackageSnapshot oldSnap, PackageSnapshot newSnap,
                               Path outDir) throws IOException {
        Path jarsDir = outDir.resolve("diff-jars");
        Files.createDirectories(jarsDir);
        for (DiffStatus st : new DiffStatus[]{DiffStatus.ADDED, DiffStatus.DELETED, DiffStatus.MODIFIED}) {
            for (String k : r.get(st)) {
                exportSingleJar(k, st, oldSnap, newSnap, jarsDir);
            }
        }
        return jarsDir;
    }

    private void exportSingleJar(String k, DiffStatus st, PackageSnapshot oldSnap,
                                 PackageSnapshot newSnap, Path jarsDir) throws IOException {
        if (!k.endsWith(".jar") || !k.contains("/lib/")) {
            return;
        }
        LogicalEntry ne = newSnap.getEntries().get(k);
        LogicalEntry oe = oldSnap.getEntries().get(k);
        LogicalEntry srcEntry = (st == DiffStatus.DELETED) ? oe : ne;
        PackageSnapshot srcSnap = (st == DiffStatus.DELETED) ? oldSnap : newSnap;
        if (srcEntry == null || srcEntry.getFileClass() == FileClass.FOLDER) {
            return;
        }
        byte[] bytes = parser.readEntryBytes(srcSnap, srcEntry);
        Path target = jarsDir.resolve(k.replace("/", "_"));
        atomicWrite(target, bytes);
    }

    /** ③ 反编译源码 zip：Top-K 完整源码 + manifest 清单。
     *  前端 JS/HTML/CSS 按原始扩展名落盘（如 .js），Java 类仍为 .java。 */
    public Path exportDecompiledSources(Map<String, DecompiledUnit> decompiled, Path outDir,
                                        int topK) throws IOException {
        Files.createDirectories(outDir);
        Path zip = outDir.resolve("decompiled-sources.zip");
        Path tmp = Files.createTempFile(outDir, ".decompiled-", ".tmp");
        int shown = 0;
        try (OutputStream fos = Files.newOutputStream(tmp);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            StringBuilder manifest = new StringBuilder();
            manifest.append("# 反编译/前端源码导出清单（Top-K=").append(topK).append("）\n");
            manifest.append("# key | engine | ok\n");
            for (Map.Entry<String, DecompiledUnit> e : decompiled.entrySet()) {
                if (shown >= topK) break;
                DecompiledUnit u = e.getValue();
                String name = e.getKey().replace("/", "_") + sourceExtension(e.getKey());
                zos.putNextEntry(new ZipEntry(name));
                String content;
                if (u.getNewSource() != null) {
                    content = u.getNewSource();
                } else if (u.getOldSource() != null) {
                    content = u.getOldSource();
                } else {
                    content = "// 反编译失败：" + u.getError();
                }
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                manifest.append(e.getKey()).append(" | ").append(u.getEngine())
                        .append(" | ").append(u.isOk()).append("\n");
                shown++;
            }
            zos.putNextEntry(new ZipEntry("MANIFEST.txt"));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        atomicMove(tmp, zip);
        return zip;
    }

    /** 按 key 推断源码导出扩展名：文本类资源（前端 JS/HTML/CSS、JSP、XML/Properties 等）
     *  用原扩展名；其余（Java 类）用 .java。 */
    private static String sourceExtension(String key) {
        for (String ext : new String[]{".jsp", ".jspx", ".tag", ".tagx",
                ".xml", ".properties", ".yml", ".yaml", ".json", ".conf", ".cfg",
                ".tld", ".xhtml", ".wsdl", ".xsl", ".xslt", ".dtd", ".vm", ".ftl",
                ".ini", ".toml", ".txt", ".csv",
                ".js", ".html", ".htm", ".css"}) {
            if (key.endsWith(ext)) return ext;
        }
        return ".java";
    }

    private static boolean isClassEntry(LogicalEntry e) {
        return e != null && e.getFileClass() == FileClass.CLASS && e.getLayer() == Layer.L1;
    }

    /**
     * ④ 增量更新资产目录（增量更新场景的主产物）：
     *  - increment/：新包中所有 ADDED/MODIFIED 的完整文件（含 L1 class 原始字节、/lib jar、资源、其他层），
     *    严格保持新包相对路径——用户解压 increment/ 直接覆盖到同结构目录即可完成增量部署；
     *  - deleted/：新包中被删除的文件（读老包字节），单独分类供用户人工处理旧包残留。
     * 不导出反编译源码/差异片段，所有文件均为源包中的原始字节（class 即编译后的 .class，可直接部署）。
     */
    public Path exportIncrement(DiffResult r, PackageSnapshot oldSnap, PackageSnapshot newSnap,
                                Path outDir) throws IOException {
        Path incDir = outDir.resolve("increment");
        Path delDir = outDir.resolve("deleted");
        Files.createDirectories(incDir);
        Files.createDirectories(delDir);
        // 新增/修改 → increment/（读新包）；删除 → deleted/（读老包）
        for (DiffStatus st : new DiffStatus[]{DiffStatus.ADDED, DiffStatus.MODIFIED}) {
            for (String k : r.get(st)) {
                exportIncrementEntry(k, newSnap, incDir);
            }
        }
        for (String k : r.get(DiffStatus.DELETED)) {
            exportIncrementEntry(k, oldSnap, delDir);
        }
        return outDir;
    }

    private void exportIncrementEntry(String k, PackageSnapshot srcSnap, Path dir) throws IOException {
        LogicalEntry entry = srcSnap.getEntries().get(k);
        if (entry == null || entry.getFileClass() == FileClass.FOLDER) {
            return;
        }
        byte[] bytes = parser.readEntryBytes(srcSnap, entry);
        // 越界防护（Zip Slip）：写盘前确认目标落在目标目录（increment/ 或 deleted/）内
        Path target = dir.resolve(k).normalize();
        Path dirNorm = dir.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(dirNorm)) {
            throw new IOException("拒绝越界写文件（Zip Slip）: " + k);
        }
        Files.createDirectories(target.getParent());
        atomicWrite(target, bytes);
    }

    /** ⑤ 将导出目录树递归打包为 zip（差异 class/jar/全量目录 + 反编译源码包统一归档）。
     *  保留相对路径；跳过 zipOut 自身，避免迭代写入自身；先写临时文件再原子落位，失败不覆盖旧包。 */
    public Path zipTree(Path root, Path zipOut) throws IOException {
        Files.createDirectories(zipOut.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(zipOut.toAbsolutePath().getParent(), ".bdfzip-", ".tmp");
        try (OutputStream fos = Files.newOutputStream(tmp);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos);
             // Files.walk 返回的资源 Stream 必须随 try-with-resources 关闭，否则文件句柄在 ZIP 写出期间持续打开
             java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.equals(zipOut) && !p.equals(tmp))
                .forEach(p -> {
                    try {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(rel));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        atomicMove(tmp, zipOut);
        return zipOut;
    }

    /** 原子落盘：先写同目录临时文件，再 move 覆盖目标；异常时清理临时文件，避免半成品覆盖旧导出。 */
    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".bdf-", ".tmp");
        try {
            Files.write(tmp, bytes);
            atomicMove(tmp, target);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
    }

    /** ATOMIC_MOVE 在跨文件系统/部分 FS 不支持时降级为普通 REPLACE_EXISTING（仍保证目标不被截断）。 */
    static void atomicMove(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

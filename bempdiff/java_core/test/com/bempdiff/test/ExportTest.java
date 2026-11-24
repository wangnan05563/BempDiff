package com.bempdiff.test;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.export.AssetExporter;
import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.EntrySource;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.Layer;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.parse.PackageParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** 差异资产导出测试：class 导出(命名空间)、Zip Slip 防护、jar 导出、反编译源码 zip(Top-K)。 */
public final class ExportTest {

    private static Map<String, byte[]> warWithThirdParty() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("WEB-INF/classes/com/internal/A.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        Map<String, byte[]> tp = new LinkedHashMap<>();
        tp.put("org/apache/T.class", TestFixtures.compileClass("org.apache.T", TestFixtures.SRC_T));
        e.put("WEB-INF/lib/third-party.jar", TestFixtures.makeZip(tp));
        return e;
    }

    public void testExportDiffClasses_namespacePreserved() throws IOException {
        Path war = TestFixtures.writePackage(TestFixtures.makeWar(warWithThirdParty(), "1"), "exp-class");
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(war, new ParseConfig(), false);
        LogicalEntry a = snap.getEntries().get("WEB-INF/classes/com/internal/A.class");

        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.MODIFIED, "WEB-INF/classes/com/internal/A.class");
        // 把 A 放进两个快照（修改类需两侧都有）
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("WEB-INF/classes/com/internal/A.class", a);
        PackageSnapshot oldSnap = new PackageSnapshot(war, snap.getType(), null, m);
        PackageSnapshot newSnap = new PackageSnapshot(war, snap.getType(), null, m);

        Path outDir = Files.createTempDirectory("bdexp");
        Path classesDir = new AssetExporter().exportDiffClasses(r, oldSnap, newSnap, outDir);
        Path target = classesDir.resolve("WEB-INF/classes/com/internal/A.class");
        Asserts.assertTrue("应保留命名空间路径导出", Files.exists(target));
        Asserts.assertTrue("导出文件不应为空", Files.size(target) > 0);
    }

    public void testExportDiffClasses_zipSlipRefused() throws IOException {
        Path war = TestFixtures.writePackage(TestFixtures.makeWar(warWithThirdParty(), "1"), "exp-slip");
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(war, new ParseConfig(), false);
        LogicalEntry a = snap.getEntries().get("WEB-INF/classes/com/internal/A.class");

        // 恶意 key 含 ".."，但 src 指向真实存在的 A（读字节成功），写盘前应被 Zip Slip 防护拦截
        LogicalEntry evil = new LogicalEntry("../escape/evil.class", Layer.L1, FileClass.CLASS,
                a.getSize(), a.getSha256(), a.getSrc());
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("../escape/evil.class", evil);
        PackageSnapshot newSnap = new PackageSnapshot(war, snap.getType(), null, m);

        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.MODIFIED, "../escape/evil.class");

        Path outDir = Files.createTempDirectory("bdexp-slip");
        boolean threw = false;
        try {
            new AssetExporter().exportDiffClasses(r, newSnap, newSnap, outDir);
        } catch (IOException e) {
            threw = e.getMessage() != null && e.getMessage().contains("Zip Slip");
        }
        Asserts.assertTrue("越界写盘应被拒绝(Zip Slip)", threw);
    }

    public void testExportDiffJars() throws IOException {
        Path war = TestFixtures.writePackage(TestFixtures.makeWar(warWithThirdParty(), "1"), "exp-jar");
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(war, new ParseConfig(), false);
        LogicalEntry tp = snap.getEntries().get("WEB-INF/lib/third-party.jar");
        Asserts.assertNotNull("third-party.jar 应存在", tp);

        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.MODIFIED, "WEB-INF/lib/third-party.jar");
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("WEB-INF/lib/third-party.jar", tp);
        PackageSnapshot newSnap = new PackageSnapshot(war, snap.getType(), null, m);

        Path outDir = Files.createTempDirectory("bdexp-jar");
        Path jarsDir = new AssetExporter().exportDiffJars(r, newSnap, newSnap, outDir);
        Path target = jarsDir.resolve("WEB-INF_lib_third-party.jar");
        Asserts.assertTrue("差异 jar 应导出", Files.exists(target));
    }

    public void testExportDecompiledSources_topK() throws IOException {
        Map<String, DecompiledUnit> decompiled = new LinkedHashMap<>();
        decompiled.put("a.class", new DecompiledUnit("a.class", "o", "n", "da", "cfr", "", true));
        decompiled.put("b.class", new DecompiledUnit("b.class", "o", "n", "db", "cfr", "", true));
        decompiled.put("c.class", new DecompiledUnit("c.class", "o", "n", "dc", "cfr", "", true));

        Path outDir = Files.createTempDirectory("bdexp-src");
        Path zip = new AssetExporter().exportDecompiledSources(decompiled, outDir, 2);
        Asserts.assertTrue("zip 应生成", Files.exists(zip));

        int entries = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) entries++;
        }
        // Top-K=2 -> 2 个 .java + 1 个 MANIFEST.txt = 3
        Asserts.assertEquals("zip 应包含 2 源码 + 1 清单", 3, entries);
    }

    /** ④ 整体导出：最终下载 zip 应同时包含 diff-classes/、diff-jars/ 与 decompiled-sources.zip，
     *  不再只下发反编译源码包（此前差异 class/jar 目录被遗漏，仅剩 MANIFEST.txt）。 */
    public void testExportFinalZip_containsAllProducts() throws IOException {
        Path war = TestFixtures.writePackage(TestFixtures.makeWar(warWithThirdParty(), "1"), "exp-final");
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(war, new ParseConfig(), false);
        LogicalEntry a = snap.getEntries().get("WEB-INF/classes/com/internal/A.class");
        LogicalEntry tp = snap.getEntries().get("WEB-INF/lib/third-party.jar");

        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.MODIFIED, "WEB-INF/classes/com/internal/A.class");
        r.put(DiffStatus.MODIFIED, "WEB-INF/lib/third-party.jar");
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("WEB-INF/classes/com/internal/A.class", a);
        m.put("WEB-INF/lib/third-party.jar", tp);
        PackageSnapshot oldSnap = new PackageSnapshot(war, snap.getType(), null, m);
        PackageSnapshot newSnap = new PackageSnapshot(war, snap.getType(), null, m);

        Path outDir = Files.createTempDirectory("bdexp-final");
        AssetExporter exporter = new AssetExporter();
        exporter.exportDiffClasses(r, oldSnap, newSnap, outDir);
        exporter.exportDiffJars(r, oldSnap, newSnap, outDir);
        Map<String, DecompiledUnit> decompiled = new LinkedHashMap<>();
        decompiled.put("b.class", new DecompiledUnit("b.class", "o", "n", "db", "cfr", "", true));
        exporter.exportDecompiledSources(decompiled, outDir, 5);
        Path finalZip = exporter.zipTree(outDir, outDir.resolve("final.zip"));

        boolean hasClass = false, hasJar = false, hasZip = false;
        try (ZipFile zf = new ZipFile(finalZip.toFile())) {
            for (ZipEntry ze : Collections.list(zf.entries())) {
                String n = ze.getName();
                if (n.startsWith("diff-classes/") && n.endsWith(".class")) hasClass = true;
                if (n.startsWith("diff-jars/") && n.endsWith(".jar")) hasJar = true;
                if (n.equals("decompiled-sources.zip")) hasZip = true;
            }
        }
        Asserts.assertTrue("最终 zip 应含 diff-classes 差异 class", hasClass);
        Asserts.assertTrue("最终 zip 应含 diff-jars 差异 jar", hasJar);
        Asserts.assertTrue("最终 zip 应含反编译源码包", hasZip);
    }

    /** ⑤ 增量资产导出（exportIncrement）：increment/ 应包含新包资源/其他层文件并保持原始相对路径，
     *  deleted/ 应单独归类新包被删文件（读老包字节），两者均为源包原始字节（非反编译源码/差异片段）。 */
    public void testExportIncrement_dirsAndPaths() throws IOException {
        Map<String, byte[]> war = new LinkedHashMap<>();
        war.put("WEB-INF/classes/com/internal/A.class",
                TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        // 普通资源（非 class、位于 L1 之外的层）——必须进入 increment/ 且保持路径
        war.put("WEB-INF/classes/META-INF/app.yml",
                "server:\n  port: 8080\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> tp = new LinkedHashMap<>();
        tp.put("org/apache/T.class", TestFixtures.compileClass("org.apache.T", TestFixtures.SRC_T));
        war.put("WEB-INF/lib/third-party.jar", TestFixtures.makeZip(tp));
        Path file = TestFixtures.writePackage(TestFixtures.makeWar(war, "1"), "exp-inc");

        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(file, new ParseConfig(), false);
        LogicalEntry yml = snap.getEntries().get("WEB-INF/classes/META-INF/app.yml");
        LogicalEntry jar = snap.getEntries().get("WEB-INF/lib/third-party.jar");
        Asserts.assertNotNull("资源 yml 应被解析为条目", yml);
        Asserts.assertNotNull("lib jar 应被解析为条目", jar);

        // 新增：app.yml（新包）；修改：third-party.jar（新包）；删除：A.class（读老包字节）
        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.ADDED, "WEB-INF/classes/META-INF/app.yml");
        r.put(DiffStatus.MODIFIED, "WEB-INF/lib/third-party.jar");
        r.put(DiffStatus.DELETED, "WEB-INF/classes/com/internal/A.class");
        Map<String, LogicalEntry> nm = new LinkedHashMap<>();
        nm.put("WEB-INF/classes/META-INF/app.yml", yml);
        nm.put("WEB-INF/lib/third-party.jar", jar);
        nm.put("WEB-INF/classes/com/internal/A.class",
                snap.getEntries().get("WEB-INF/classes/com/internal/A.class"));
        PackageSnapshot oldSnap = new PackageSnapshot(file, snap.getType(), null, nm);
        PackageSnapshot newSnap = new PackageSnapshot(file, snap.getType(), null, nm);

        Path outDir = Files.createTempDirectory("bdexp-inc");
        AssetExporter exporter = new AssetExporter();
        exporter.exportIncrement(r, oldSnap, newSnap, outDir);

        Asserts.assertTrue("increment 应含新增资源并保持路径",
                Files.exists(outDir.resolve("increment/WEB-INF/classes/META-INF/app.yml")));
        Asserts.assertTrue("increment 应含修改 jar 并保持路径",
                Files.exists(outDir.resolve("increment/WEB-INF/lib/third-party.jar")));
        Asserts.assertTrue("deleted 应含被删类（读老包字节）并保持路径",
                Files.exists(outDir.resolve("deleted/WEB-INF/classes/com/internal/A.class")));
    }

    /** ⑤b 文件夹对比会把目录节点计入差异：FOLDER 节点无字节内容，应被跳过而非抛异常。 */
    public void testExportIncrement_skipsFolderNodes() throws IOException {
        Path war = TestFixtures.writePackage(TestFixtures.makeWar(warWithThirdParty(), "1"), "exp-foldnode2");
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(war, new ParseConfig(), false);
        LogicalEntry a = snap.getEntries().get("WEB-INF/classes/com/internal/A.class");
        LogicalEntry folder = new LogicalEntry("config/", Layer.L1, FileClass.FOLDER,
                a.getSize(), a.getSha256(), a.getSrc());
        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.ADDED, "config/");
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("config/", folder);
        PackageSnapshot newSnap = new PackageSnapshot(war, snap.getType(), null, m);
        Path outDir = Files.createTempDirectory("bdexp-foldnode2");
        new AssetExporter().exportIncrement(r, newSnap, newSnap, outDir);
        Asserts.assertFalse("FOLDER 节点不应生成文件",
                Files.exists(outDir.resolve("increment/config/")));
    }

    /** ⑤c 最终打包 zip：应同时包含 increment/ 与 deleted/ 两个增量目录，不再有 diff-classes/diff-all。 */
    public void testExportFinalZip_containsIncrementDirs() throws IOException {
        Path war = TestFixtures.writePackage(TestFixtures.makeWar(warWithThirdParty(), "1"), "exp-final2");
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(war, new ParseConfig(), false);
        LogicalEntry a = snap.getEntries().get("WEB-INF/classes/com/internal/A.class");
        LogicalEntry tp = snap.getEntries().get("WEB-INF/lib/third-party.jar");
        // 老包独有、新包缺失的条目 → DELETED（deleted/ 需有真实文件，空目录不会写入 zip）
        LogicalEntry b = new LogicalEntry("WEB-INF/classes/com/internal/B.class", Layer.L1, FileClass.CLASS,
                a.getSize(), a.getSha256(), a.getSrc());

        DiffResult r = new DiffResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        r.put(DiffStatus.MODIFIED, "WEB-INF/classes/com/internal/A.class");
        r.put(DiffStatus.MODIFIED, "WEB-INF/lib/third-party.jar");
        r.put(DiffStatus.DELETED, "WEB-INF/classes/com/internal/B.class");
        Map<String, LogicalEntry> om = new LinkedHashMap<>();
        om.put("WEB-INF/classes/com/internal/A.class", a);
        om.put("WEB-INF/lib/third-party.jar", tp);
        om.put("WEB-INF/classes/com/internal/B.class", b);
        Map<String, LogicalEntry> nm = new LinkedHashMap<>();
        nm.put("WEB-INF/classes/com/internal/A.class", a);
        nm.put("WEB-INF/lib/third-party.jar", tp);
        PackageSnapshot oldSnap = new PackageSnapshot(war, snap.getType(), null, om);
        PackageSnapshot newSnap = new PackageSnapshot(war, snap.getType(), null, nm);

        Path outDir = Files.createTempDirectory("bdexp-final2");
        AssetExporter exporter = new AssetExporter();
        exporter.exportIncrement(r, oldSnap, newSnap, outDir);
        Path finalZip = exporter.zipTree(outDir, outDir.resolve("final.zip"));

        boolean hasInc = false, hasDelFile = false, hasLegacy = false;
        try (ZipFile zf = new ZipFile(finalZip.toFile())) {
            for (ZipEntry ze : Collections.list(zf.entries())) {
                String n = ze.getName();
                if (n.startsWith("increment/")) hasInc = true;
                if (n.startsWith("deleted/") && n.endsWith(".class")) hasDelFile = true;
                if (n.startsWith("diff-classes/") || n.startsWith("diff-all/")
                        || n.equals("decompiled-sources.zip")) hasLegacy = true;
            }
        }
        Asserts.assertTrue("最终 zip 应含 increment/ 目录", hasInc);
        Asserts.assertTrue("最终 zip 应含 deleted/ 被删文件", hasDelFile);
        Asserts.assertFalse("最终 zip 不应再有旧 diff-classes/diff-all/decompiled-sources 产物", hasLegacy);
    }

    /** 导出规模估算 + 「大包」判定（同步/异步分流依据，Java 服务端纯函数）。 */
    public void testExportEstimateAndLarge() {
        try {
            java.util.Map<String, LogicalEntry> old = new java.util.LinkedHashMap<>();
            java.util.Map<String, LogicalEntry> now = new java.util.LinkedHashMap<>();
            EntrySource ns = new EntrySource("x", null);
            old.put("WEB-INF/lib/a.jar", new LogicalEntry("a", Layer.L1, FileClass.CLASS, 100, "shaA", ns));
            old.put("WEB-INF/classes/d.class", new LogicalEntry("d", Layer.L1, FileClass.CLASS, 50, "shaD", ns));
            now.put("WEB-INF/lib/a.jar", new LogicalEntry("a", Layer.L1, FileClass.CLASS, 120, "shaA2", ns));
            now.put("WEB-INF/classes/c.class", new LogicalEntry("c", Layer.L1, FileClass.CLASS, 30, "shaC", ns));
            PackageSnapshot os = new PackageSnapshot(java.nio.file.Paths.get("old.zip"), com.bempdiff.model.PackageType.WAR, "1", old);
            PackageSnapshot nw = new PackageSnapshot(java.nio.file.Paths.get("new.zip"), com.bempdiff.model.PackageType.WAR, "1", now);
            DiffResult r = new com.bempdiff.diff.DiffEngine().compute(os, nw);
            java.util.Map<String, Object> est = com.bempdiff.server.BempServer.exportEstimate(r, old, now);
            Asserts.assertEquals("files=修改+新增+删除", 3L, ((Number) est.get("files")).longValue());
            Asserts.assertEquals("bytes=120+30+50", 200L, ((Number) est.get("bytes")).longValue());
            // 大包判定：文件数/字节任意超限即异步
            Asserts.assertFalse("小包不应判为大包", com.bempdiff.server.BempServer.isLargeExport(200, 5L * 1024 * 1024));
            Asserts.assertTrue("文件数超限应判大包", com.bempdiff.server.BempServer.isLargeExport(20001, 10));
            Asserts.assertTrue("字节超限应判大包", com.bempdiff.server.BempServer.isLargeExport(10, 300L * 1024 * 1024));
        } catch (Exception ex) {
            Asserts.fail("导出估算测试失败: " + ex);
        }
    }

    /** T06232：zipTree 先写临时文件再原子落位；旧包不应被半成品覆盖，且不残留 .bdfzip 临时文件。 */
    public void testZipTree_atomicReplaceLeavesNoTempFile() throws IOException {
        Path root = Files.createTempDirectory("bdexp-atomic-src");
        Files.writeString(root.resolve("a.txt"), "A", StandardCharsets.UTF_8);
        Path outDir = Files.createTempDirectory("bdexp-atomic-out");
        Path zipOut = outDir.resolve("assets.zip");
        Files.writeString(zipOut, "OLD-CORRUPT-PLACEHOLDER", StandardCharsets.UTF_8);

        new AssetExporter().zipTree(root, zipOut);

        Asserts.assertTrue("旧包应被新包替换", Files.size(zipOut) > 0);
        boolean found = false;
        try (ZipFile zf = new ZipFile(zipOut.toFile())) {
            found = zf.getEntry("a.txt") != null;
        }
        Asserts.assertTrue("新包应包含导出文件", found);
        try (java.util.stream.Stream<Path> s = Files.list(outDir)) {
            Asserts.assertFalse("不应残留 .bdfzip 临时文件",
                    s.anyMatch(p -> p.getFileName().toString().startsWith(".bdfzip-")));
        }
    }
}

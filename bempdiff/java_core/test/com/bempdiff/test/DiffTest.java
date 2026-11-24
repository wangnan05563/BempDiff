package com.bempdiff.test;

import com.bempdiff.diff.DiffEngine;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.diff.LineDiff;
import com.bempdiff.model.EntrySource;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.Layer;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 差异计算测试：增/删/改/未变判定、统计（含业务/ jar 级拆分）、L1 class 候选收集。 */
public final class DiffTest {

    private static LogicalEntry le(String key, Layer layer, FileClass fc, String sha) {
        return new LogicalEntry(key, layer, fc, sha.length(), sha, new EntrySource(key, null));
    }

    private static PackageSnapshot snap(Map<String, LogicalEntry> entries) {
        return new PackageSnapshot(Path.of("dummy"), null, null, entries);
    }

    /** T01810 FR-006 展示层：AST 口径折叠的 .java 旁路标记「格式或位置变更」，与行级折叠/实质变更区分。 */
    public void testCompute_formattedOnlyMarker() throws Exception {
        String v1 = """
                package com.demo;
                // 头注释
                public class Svc {
                    private int count = 1;
                    public String hello(String name) { return "hi, " + name; }
                    public void run() { int x = 1; }
                }
                """;
        // 重排 + 重新格式化 + 注释改写：AST 归一化相等 → UNCHANGED + formattedOnly
        String v2Reordered = """
                package com.demo;
                /* 新注释 */
                public class Svc {
                    public void run() { int x = 1; }
                    private int count = 1;
                    public String hello(String name) { return "hi, " + name; }
                }
                """;
        // 实质变更：方法体不同 → MODIFIED 且不标 formattedOnly
        String v3Substantive = v1.replace("return \"hi, \" + name;", "return \"hello, \" + name;");

        java.nio.file.Path f1 = java.nio.file.Files.createTempFile("bempdiff-fmt1-", ".java");
        java.nio.file.Path f2 = java.nio.file.Files.createTempFile("bempdiff-fmt2-", ".java");
        java.nio.file.Path f3 = java.nio.file.Files.createTempFile("bempdiff-fmt3-", ".java");
        java.nio.file.Path f4 = java.nio.file.Files.createTempFile("bempdiff-fmt4-", ".java");
        java.nio.file.Files.write(f1, v1.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(f2, v2Reordered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(f3, v1.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(f4, v3Substantive.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // CRLF 版：行级折叠（textuallySameEntries），不应标 formattedOnly（口径只标 AST 层）
        java.nio.file.Path f5 = java.nio.file.Files.createTempFile("bempdiff-fmt5-", ".java");
        java.nio.file.Files.write(f5, v1.replace("\n", "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        old.put("src/Svc.java", e("src/Svc.java", f1));
        old.put("src/Sub.java", e("src/Sub.java", f3));
        old.put("src/Eol.java", e("src/Eol.java", f3));
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        now.put("src/Svc.java", e("src/Svc.java", f2));
        now.put("src/Sub.java", e("src/Sub.java", f4));
        now.put("src/Eol.java", e("src/Eol.java", f5));

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertContains("重排+格式化应折叠为未变", r.get(DiffStatus.UNCHANGED).toString(), "src/Svc.java");
        Asserts.assertTrue("AST 折叠条目应标记 formattedOnly", r.isFormattedOnly("src/Svc.java"));
        Asserts.assertContains("实质变更应保持修改", r.get(DiffStatus.MODIFIED).toString(), "src/Sub.java");
        Asserts.assertTrue("实质变更不应标记 formattedOnly", !r.isFormattedOnly("src/Sub.java"));
        Asserts.assertContains("仅行尾差异应折叠为未变", r.get(DiffStatus.UNCHANGED).toString(), "src/Eol.java");
        Asserts.assertTrue("行级折叠不应标记 formattedOnly（口径仅 AST 层）", !r.isFormattedOnly("src/Eol.java"));
        Asserts.assertEquals("计数应恰为 1", 1, r.formattedOnlyCount());
    }

    /** 构造 folder 模式 .java 条目（EntrySource 指向磁盘真实文件，供 AST 判定读内容）。 */
    private static LogicalEntry e(String key, java.nio.file.Path f) throws java.io.IOException {
        return new LogicalEntry(key, Layer.L0, FileClass.OTHER,
                java.nio.file.Files.size(f), "sha-" + key + "-" + f.getFileName(),
                new EntrySource(f.toString(), null));
    }

    /** 仅行尾(CRLF/LF)差异的文本(.sh)应视为无差异；内容确不同才判修改（与 diff 视图口径一致，避免误进导出/破坏性）。 */
    public void testCompute_lineEndingOnlyShIsUnchanged() throws Exception {
        java.nio.file.Path oldF = java.nio.file.Files.createTempFile("bempdiff-old-", ".sh");
        java.nio.file.Path newF = java.nio.file.Files.createTempFile("bempdiff-new-", ".sh");
        String body = "#!/bin/sh\necho hi\nexit 0\n";
        java.nio.file.Files.write(oldF, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(newF, body.replace("\n", "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        old.put("script/run.sh", new LogicalEntry("script/run.sh", Layer.L0, FileClass.CONFIG,
                java.nio.file.Files.size(oldF), "sha_old_crlf", new EntrySource(oldF.toString(), null)));
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        now.put("script/run.sh", new LogicalEntry("script/run.sh", Layer.L0, FileClass.CONFIG,
                java.nio.file.Files.size(newF), "sha_new_lf", new EntrySource(newF.toString(), null)));
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertContains("仅行尾差异→未变(不进导出/破坏性)",
                r.get(DiffStatus.UNCHANGED).toString(), "script/run.sh");
        Asserts.assertFalse("仅行尾差异不应判为修改",
                r.get(DiffStatus.MODIFIED).contains("script/run.sh"));

        // 内容确不同（新增一行）→ 应判修改
        java.nio.file.Files.write(newF, (body + "echo extra\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, LogicalEntry> now2 = new LinkedHashMap<>();
        now2.put("script/run.sh", new LogicalEntry("script/run.sh", Layer.L0, FileClass.CONFIG,
                java.nio.file.Files.size(newF), "sha_new2", new EntrySource(newF.toString(), null)));
        DiffResult r2 = new DiffEngine().compute(snap(old), snap(now2));
        Asserts.assertContains("内容确不同→修改", r2.get(DiffStatus.MODIFIED).toString(), "script/run.sh");
    }

    /** folder 模式下 .java 源文件归类 JAVA，仅行尾 CRLF/LF 差异应视为无差异（回归 T00834 同源实报：内容一致的 Java 文件被误判有差异）。 */
    public void testCompute_lineEndingOnlyJavaSrcIsUnchanged() throws Exception {
        java.nio.file.Path oldF = java.nio.file.Files.createTempFile("bempdiff-old-", ".java");
        java.nio.file.Path newF = java.nio.file.Files.createTempFile("bempdiff-new-", ".java");
        String body = "package com.x;\npublic class A {\n    private int n;\n}\n";
        java.nio.file.Files.write(oldF, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(newF, body.replace("\n", "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        // folder 模式：.java → FileClass.JAVA，EntrySource.outerEntry 指向磁盘真实文件
        old.put("src/A.java", new LogicalEntry("src/A.java", Layer.L0, FileClass.JAVA,
                java.nio.file.Files.size(oldF), "sha_old_crlf", new EntrySource(oldF.toString(), null)));
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        now.put("src/A.java", new LogicalEntry("src/A.java", Layer.L0, FileClass.JAVA,
                java.nio.file.Files.size(newF), "sha_new_lf", new EntrySource(newF.toString(), null)));
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertContains("Java 源仅行尾差异→未变(不误判修改)", r.get(DiffStatus.UNCHANGED).toString(), "src/A.java");
        Asserts.assertFalse("Java 源仅行尾差异不应判为修改", r.get(DiffStatus.MODIFIED).contains("src/A.java"));

        // 内容确不同（新增一行）→ 应判修改
        java.nio.file.Files.write(newF, (body + "    private String s;\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, LogicalEntry> now2 = new LinkedHashMap<>();
        now2.put("src/A.java", new LogicalEntry("src/A.java", Layer.L0, FileClass.JAVA,
                java.nio.file.Files.size(newF), "sha_new2", new EntrySource(newF.toString(), null)));
        DiffResult r2 = new DiffEngine().compute(snap(old), snap(now2));
        Asserts.assertContains("Java 源内容确不同→修改", r2.get(DiffStatus.MODIFIED).toString(), "src/A.java");
    }

    /** 通用化回归：folder 模式下归入 OTHER 的其他纯文本类型（.sql/.md/.log）仅行尾差异也应判未变，而不只限于 .java。 */
    public void testCompute_lineEndingOnlyOtherTextIsUnchanged() throws Exception {
        String[] bases = { "db/update.sql" };
        for (String pathBase : bases) {
            java.nio.file.Path oF = java.nio.file.Files.createTempFile("bempdiff-old-", ".bin");
            java.nio.file.Path nF = java.nio.file.Files.createTempFile("bempdiff-new-", ".bin");
            String body = "CREATE TABLE t(a int);\nINSERT INTO t VALUES(1);\n";
            java.nio.file.Files.write(oF, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(nF, body.replace("\n", "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, LogicalEntry> old = new LinkedHashMap<>();
            old.put(pathBase, new LogicalEntry(pathBase, Layer.L0, FileClass.OTHER,
                    java.nio.file.Files.size(oF), "sha_old_crlf", new EntrySource(oF.toString(), null)));
            Map<String, LogicalEntry> now = new LinkedHashMap<>();
            now.put(pathBase, new LogicalEntry(pathBase, Layer.L0, FileClass.OTHER,
                    java.nio.file.Files.size(nF), "sha_new_lf", new EntrySource(nF.toString(), null)));
            DiffResult r = new DiffEngine().compute(snap(old), snap(now));
            Asserts.assertContains(pathBase + " 仅行尾差异→未变", r.get(DiffStatus.UNCHANGED).toString(), pathBase);
            Asserts.assertFalse(pathBase + " 不应误判为修改", r.get(DiffStatus.MODIFIED).contains(pathBase));

            // 内容确不同 → 修改
            java.nio.file.Files.write(nF, (body + "COMMIT;\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, LogicalEntry> now2 = new LinkedHashMap<>();
            now2.put(pathBase, new LogicalEntry(pathBase, Layer.L0, FileClass.OTHER,
                    java.nio.file.Files.size(nF), "sha_new2", new EntrySource(nF.toString(), null)));
            DiffResult r2 = new DiffEngine().compute(snap(old), snap(now2));
            Asserts.assertContains(pathBase + " 内容确不同→修改", r2.get(DiffStatus.MODIFIED).toString(), pathBase);
        }
    }

    public void testCompute_versionedNestedWarPairs() {
        // 外层 zip 内嵌套 "同基名不同版本" 的 war（bemp-web-5....M.15.war ↔ M.17.war），
        // 应为「一个容器（旧侧条目标记处理）」，而不是被各自误识别为删除/新增。
        java.util.Map<String, LogicalEntry> old = new LinkedHashMap<>();
        java.util.Map<String, LogicalEntry> now = new LinkedHashMap<>();
        old.put("deliver.zip!/bemp-web-5.20230102036M.15.war",
                new LogicalEntry("deliver.zip!/bemp-web-5.20230102036M.15.war", Layer.L0, FileClass.ARCHIVE, 0, "sha_html15", new EntrySource("x", null)));
        now.put("deliver.zip!/bemp-web-5.20230102036M.17.war",
                new LogicalEntry("deliver.zip!/bemp-web-5.20230102036M.17.war", Layer.L0, FileClass.ARCHIVE, 0, "sha_html17", new EntrySource("x", null)));
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertFalse("M.15.war 不应残留为删除",
                r.get(DiffStatus.DELETED).contains("deliver.zip!/bemp-web-5.20230102036M.15.war"));
        Asserts.assertFalse("M.17.war 不应残留为新增",
                r.get(DiffStatus.ADDED).contains("deliver.zip!/bemp-web-5.20230102036M.17.war"));
    }

    public void testCompute_versionedOuterZipNestedWarPairs() {
        // 两个版本化外层交付 zip（M059/M061），各自内含同基名不同版本 war（M.15/M.17）：
        // 外层 zip 配对后，嵌套 war 应同样配对为一个容器（旧键保留、非删除/新增），其同哈希内部文件折叠为未变。
        java.util.Map<String, LogicalEntry> old = new LinkedHashMap<>();
        java.util.Map<String, LogicalEntry> now = new LinkedHashMap<>();
        final String oz = "BEMP5.0V202301-02-036M059(20260703-1104).zip";
        final String nz = "BEMP5.0V202301-02-036M061(20260707-1135).zip";
        final String ow = oz + "!/bemp-web-5.20230102036M.15.war";
        final String nw = nz + "!/bemp-web-5.20230102036M.17.war";
        old.put(oz, new LogicalEntry(oz, Layer.L0, FileClass.ARCHIVE, 0, "sha_oz", new EntrySource("x", null)));
        old.put(ow, new LogicalEntry(ow, Layer.L0, FileClass.ARCHIVE, 0, "sha_ow", new EntrySource("x", null)));
        old.put(ow + "!/WEB-INF/classes/A.class", new LogicalEntry(ow + "!/WEB-INF/classes/A.class", Layer.L2, FileClass.CLASS, 0, "shaC", new EntrySource("x", null)));
        now.put(nz, new LogicalEntry(nz, Layer.L0, FileClass.ARCHIVE, 0, "sha_nz", new EntrySource("x", null)));
        now.put(nw, new LogicalEntry(nw, Layer.L0, FileClass.ARCHIVE, 0, "sha_nw", new EntrySource("x", null)));
        now.put(nw + "!/WEB-INF/classes/A.class", new LogicalEntry(nw + "!/WEB-INF/classes/A.class", Layer.L2, FileClass.CLASS, 0, "shaC", new EntrySource("x", null)));
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertFalse("外层 zip 不应残留删除", r.get(DiffStatus.DELETED).contains(oz));
        Asserts.assertFalse("嵌套 M.15.war 不应残留删除", r.get(DiffStatus.DELETED).contains(ow));
        Asserts.assertFalse("嵌套 M.17.war 不应残留新增", r.get(DiffStatus.ADDED).contains(nw));
        Asserts.assertFalse("同哈希内部 A.class 不应残留删除", r.get(DiffStatus.DELETED).contains(ow + "!/WEB-INF/classes/A.class"));
    }

    public void testCompute_versionAffixBoundary() {
        // 版本等价判定的边界（回归钉住）：数字前夹带 ≥2 字母单词的段是「不同产品/词缀」而非版本增量——
        // ① 产品名 web 与 adapter 互不视为版本等价：同内容 start.sh 各归各侧折叠，不被对方污染；
        // ② 纯版本尾段 -rc1/-rc2 仍视为版本等价，折叠为未变；
        // ③ logo → logo-login 因词缀不同不折叠，保留为删除/新增（正确视为改名）。
        java.util.Map<String, LogicalEntry> old = new LinkedHashMap<>();
        java.util.Map<String, LogicalEntry> now = new LinkedHashMap<>();
        String webOld = "BEMP5.0-webV202301-02-036M059(20260703-1104).zip/scripts/start.sh";
        String webNew = "BEMP5.0-webV202301-02-036M061(20260707-1135).zip/scripts/start.sh";
        String adpOld = "BEMP5.0-adapterV202301-02-036M059(20260703-1104).zip/scripts/start.sh";
        String adpNew = "BEMP5.0-adapterV202301-02-036M061(20260707-1135).zip/scripts/start.sh";
        // 4 个 start.sh 字节全同（sha 相同）
        old.put(webOld, le(webOld, Layer.L0, FileClass.CONFIG, "shaS"));
        old.put(adpOld, le(adpOld, Layer.L0, FileClass.CONFIG, "shaS"));
        now.put(webNew, le(webNew, Layer.L0, FileClass.CONFIG, "shaS"));
        now.put(adpNew, le(adpNew, Layer.L0, FileClass.CONFIG, "shaS"));
        // ② -rc1/-rc2 同内容 → 应折叠为未变
        old.put("app/foo-rc1.txt", le("app/foo-rc1.txt", Layer.L0, FileClass.CONFIG, "shaR"));
        now.put("app/foo-rc2.txt", le("app/foo-rc2.txt", Layer.L0, FileClass.CONFIG, "shaR"));
        // ③ logo 与 logo-login 词缀不同 → 不应折叠
        old.put("img/logo.dce2e9f.png", le("img/logo.dce2e9f.png", Layer.L0, FileClass.STATIC, "shaL"));
        now.put("img/logo-login.dce2e9f.png", le("img/logo-login.dce2e9f.png", Layer.L0, FileClass.STATIC, "shaL"));
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertFalse("web start.sh 应折叠为未变（不被 adapter 同内容污染）", r.get(DiffStatus.DELETED).contains(webOld));
        Asserts.assertFalse("adapter start.sh(old) 应折叠为未变", r.get(DiffStatus.DELETED).contains(adpOld));
        Asserts.assertFalse("adapter start.sh(new) 应折叠为未变", r.get(DiffStatus.ADDED).contains(adpNew));
        Asserts.assertFalse("-rc1 → -rc2 版本等价应折叠为未变", r.get(DiffStatus.DELETED).contains("app/foo-rc1.txt"));
        Asserts.assertTrue("logo → logo-login 词缀不同应保留为删除", r.get(DiffStatus.DELETED).contains("img/logo.dce2e9f.png"));
    }

    public void testCompute_statuses() {
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        old.put("u.class", le("u.class", Layer.L1, FileClass.CLASS, "h0"));
        old.put("m.class", le("m.class", Layer.L1, FileClass.CLASS, "h1"));
        old.put("d.class", le("d.class", Layer.L1, FileClass.CLASS, "h5"));
        now.put("u.class", le("u.class", Layer.L1, FileClass.CLASS, "h0")); // 未变
        now.put("m.class", le("m.class", Layer.L1, FileClass.CLASS, "h3")); // 修改
        now.put("a.class", le("a.class", Layer.L1, FileClass.CLASS, "h4")); // 新增

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertEquals("未变=1", 1, r.get(DiffStatus.UNCHANGED).size());
        Asserts.assertEquals("修改=1", 1, r.get(DiffStatus.MODIFIED).size());
        Asserts.assertEquals("新增=1", 1, r.get(DiffStatus.ADDED).size());
        Asserts.assertEquals("删除=1", 1, r.get(DiffStatus.DELETED).size());
        Asserts.assertContains("m 应为修改", r.get(DiffStatus.MODIFIED).toString(), "m.class");
        Asserts.assertContains("d 应为删除", r.get(DiffStatus.DELETED).toString(), "d.class");
    }

    /** 同名不同版本归档（如 lib/app-1.0.zip → app-2.0.zip）应配对对齐：容器及内部文件不再全判删除+新增。 */
    public void testCompute_pairsVersionRenamedArchive() {
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        String zip = "WEB-INF/lib/";
        // 旧版本归档及其内部文件
        old.put(zip + "app-1.0.zip", le(zip + "app-1.0.zip", Layer.L0, FileClass.ARCHIVE, "zipOld"));
        old.put(zip + "app-1.0.zip!/config/application.properties", le("p", Layer.L0, FileClass.CONFIG, "SHA_SAME"));
        old.put(zip + "app-1.0.zip!/code/A.class", le("cls", Layer.L1, FileClass.CLASS, "oldCode"));
        // 新版本归档：内部 properties 内容一致（同 sha），class 内容发生变化
        now.put(zip + "app-2.0.zip", le(zip + "app-2.0.zip", Layer.L0, FileClass.ARCHIVE, "zipNew"));
        now.put(zip + "app-2.0.zip!/config/application.properties", le("p", Layer.L0, FileClass.CONFIG, "SHA_SAME"));
        now.put(zip + "app-2.0.zip!/code/A.class", le("cls", Layer.L1, FileClass.CLASS, "newCode"));

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertEquals("版本配对后不再有删除", 0, r.get(DiffStatus.DELETED).size());
        Asserts.assertEquals("版本配对后不再有新增", 0, r.get(DiffStatus.ADDED).size());
        Asserts.assertContains("容器应判为修改(字节不同)", r.get(DiffStatus.MODIFIED).toString(), zip + "app-1.0.zip");
        Asserts.assertNotContains("容器不再新增 app-2.0.zip", r.get(DiffStatus.ADDED).toString(), "app-2.0.zip");
        Asserts.assertContains("内容一致的内部 properties 应为未变", r.get(DiffStatus.UNCHANGED).toString(),
                zip + "app-1.0.zip!/config/application.properties");
        Asserts.assertContains("内容变化的内部 class 应为修改", r.get(DiffStatus.MODIFIED).toString(),
                zip + "app-1.0.zip!/code/A.class");
    }

    /** 整包交付件对比：根目录随版本改名（…M059 → …M061）导致全量键不相等。
     *  大部分内容一致的普通文件/内层 jar 内部类应归为 UNCHANGED；仅真实增删与容器变化保留差异。 */
    public void testCompute_versionedRootPackageLayout() {
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        String R59 = "BEMP5.0V202301-02-036M059(20260703-1104)/";
        String R61 = "BEMP5.0V202301-02-036M061(20260707-1135)/";
        String z59 = "BEMP5.0-webV202301-02-036M059(20260703-1104).zip";
        String z61 = "BEMP5.0-webV202301-02-036M061(20260707-1135).zip";
        // 版本化根下：内容一致的普通文件
        old.put(R59 + "bemp-home/src/main/resources/all/acctrecord/hnnxbank/acctrecord.xml",
                le("a", Layer.L0, FileClass.CONFIG, "SHA_SAME"));
        now.put(R61 + "bemp-home/src/main/resources/all/acctrecord/hnnxbank/acctrecord.xml",
                le("a", Layer.L0, FileClass.CONFIG, "SHA_SAME"));
        old.put(R59 + "detail/detail-BEMP5.0V202301-02-036M058(20260618-1334).xls",
                le("d", Layer.L0, FileClass.CONFIG, "SHA_SAME"));
        now.put(R61 + "detail/detail-BEMP5.0V202301-02-036M058(20260618-1334).xls",
                le("d", Layer.L0, FileClass.CONFIG, "SHA_SAME"));
        // 内层归档同名不同版本（容器字节变化）
        old.put(R59 + z59, le("zip", Layer.L0, FileClass.ARCHIVE, "Z_OLD"));
        now.put(R61 + z61, le("zip", Layer.L0, FileClass.ARCHIVE, "Z_NEW"));
        old.put(R59 + z59 + "!/WEB-INF/classes/biz/A.class",
                le("cls", Layer.L1, FileClass.CLASS, "C_SAME"));
        now.put(R61 + z61 + "!/WEB-INF/classes/biz/A.class",
                le("cls", Layer.L1, FileClass.CLASS, "C_SAME"));
        // 真实新增（新版本引入 detail-M060）与真实删除（旧版本独有 config）
        now.put(R61 + "detail/detail-BEMP5.0V202301-02-036M060(20260703-1819).xls",
                le("d2", Layer.L0, FileClass.CONFIG, "SHA_NEW"));
        old.put(R59 + "config/legacy.xml", le("c", Layer.L0, FileClass.CONFIG, "SHA_OLD"));

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertEquals("版本化根目录下大部分文件不再误判新增", 1, r.get(DiffStatus.ADDED).size());
        Asserts.assertContains("真实新增保留", r.get(DiffStatus.ADDED).toString(), "036M060(20260703-1819)");
        Asserts.assertEquals("版本化根目录下不再误判删除", 1, r.get(DiffStatus.DELETED).size());
        Asserts.assertContains("真实删除保留", r.get(DiffStatus.DELETED).toString(), "config/legacy.xml");
        Asserts.assertContains("容器判为修改(版本化)", r.get(DiffStatus.MODIFIED).toString(), R59 + z59);
        Asserts.assertNotContains("新容器不再新增", r.get(DiffStatus.ADDED).toString(), z61);
        // 内容一致的文件归为未变
        Asserts.assertContains("普通文件内容一致→未变", r.get(DiffStatus.UNCHANGED).toString(), "acctrecord.xml");
        Asserts.assertContains("内层同内容 class→未变", r.get(DiffStatus.UNCHANGED).toString(),
                R59 + z59 + "!/WEB-INF/classes/biz/A.class");
        Asserts.assertContains("detail-M058 内容一致→未变", r.get(DiffStatus.UNCHANGED).toString(), "036M058(20260618-1334)");
    }

    public void testStats_bizVsJar() {
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        old.put("b.class", le("b.class", Layer.L1, FileClass.CLASS, "h2"));
        old.put("WEB-INF/lib/x.jar", le("WEB-INF/lib/x.jar", Layer.L2, FileClass.JAR, "h6"));
        old.put("u.class", le("u.class", Layer.L1, FileClass.CLASS, "h0"));
        old.put("d.class", le("d.class", Layer.L1, FileClass.CLASS, "h5"));       // 删除
        now.put("b.class", le("b.class", Layer.L1, FileClass.CLASS, "h3"));       // 业务修改
        now.put("WEB-INF/lib/x.jar", le("WEB-INF/lib/x.jar", Layer.L2, FileClass.JAR, "h7")); // jar 修改
        now.put("c.class", le("c.class", Layer.L1, FileClass.CLASS, "h4"));       // 新增
        now.put("u.class", le("u.class", Layer.L1, FileClass.CLASS, "h0"));       // 未变

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        DiffStats s = new DiffEngine().stats(r);
        Asserts.assertEquals("added", 1, s.getAdded());
        Asserts.assertEquals("deleted", 1, s.getDeleted());
        Asserts.assertEquals("modified", 2, s.getModified());
        Asserts.assertEquals("unchanged", 1, s.getUnchanged());
        // 业务变更 = b(改) + c(增) + d(删) = 3；jar 变更 = x.jar = 1
        Asserts.assertEquals("bizChanged", 3, s.getBizChanged());
        Asserts.assertEquals("jarChanged", 1, s.getJarChanged());
    }

    public void testCollectL1ClassCandidates() {
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        old.put("b.class", le("b.class", Layer.L1, FileClass.CLASS, "h2"));
        old.put("x.jar", le("WEB-INF/lib/x.jar", Layer.L2, FileClass.JAR, "h6"));
        old.put("d.class", le("d.class", Layer.L1, FileClass.CLASS, "h5"));
        old.put("u.class", le("u.class", Layer.L1, FileClass.CLASS, "h0"));
        now.put("b.class", le("b.class", Layer.L1, FileClass.CLASS, "h3"));
        now.put("x.jar", le("WEB-INF/lib/x.jar", Layer.L2, FileClass.JAR, "h7"));
        now.put("c.class", le("c.class", Layer.L1, FileClass.CLASS, "h4"));
        now.put("u.class", le("u.class", Layer.L1, FileClass.CLASS, "h0"));

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        List<String> cands = DiffEngine.collectL1ClassCandidates(r, snap(old), snap(now));
        Asserts.assertEquals("候选应为 3 个 L1 class", 3, cands.size());
        Asserts.assertContains("含 b", cands.toString(), "b.class");
        Asserts.assertContains("含 c", cands.toString(), "c.class");
        Asserts.assertContains("含 d", cands.toString(), "d.class");
        Asserts.assertNotContains("不应含未变的 u", cands.toString(), "u.class");
        Asserts.assertNotContains("不应含 jar", cands.toString(), "x.jar");
    }

    /** 大批量版本改名折叠（回归护栏）：整包扁平化后可达几万条类文件，仅因根/内层版本段异构而键不等。
     *  折叠必须近 O(N) 完成（sha 索引，非同 sha 全双重遍历），否则本用例会拖死整个测试套件。 */
    public void testCompute_scaleVersionedFoldNotQuadratic() {
        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        String R59 = "BEMP5.0-webV202301-02-036M059(20260703-1104).zip/WEB-INF/classes/biz/";
        String R61 = "BEMP5.0-webV202301-02-036M061(20260707-1135).zip/WEB-INF/classes/biz/";
        int n = 15_000; // 每侧 1.5 万条唯一内容类：O(n²)=2.25 亿次路径比较，回退即卡顿可感
        for (int i = 0; i < n; i++) {
            String name = "Cls" + i + ".class";
            old.put(R59 + name, le(R59 + name, Layer.L1, FileClass.CLASS, "SHA" + i));
            now.put(R61 + name, le(R61 + name, Layer.L1, FileClass.CLASS, "SHA" + i)); // 内容唯一且一致
        }
        long t0 = System.currentTimeMillis();
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        long ms = System.currentTimeMillis() - t0;
        Asserts.assertEquals("1.5 万版本改名文件应全部折叠为未变", n, r.get(DiffStatus.UNCHANGED).size());
        Asserts.assertEquals("不应残留新增", 0, r.get(DiffStatus.ADDED).size());
        Asserts.assertEquals("不应残留删除", 0, r.get(DiffStatus.DELETED).size());
        Asserts.assertTrue("近 O(N) 完成（阈值宽松，仅挡 O(n²) 卡死）", ms < 5_000);
    }

    /** 超大文件（LCS 单元格超限）→ 退化为线性 diff：不 OOM、输出有界、含增删行。 */
    public void testLineDiff_hugeInputFallsBackToLinear() {
        StringBuilder oldB = new StringBuilder(), newB = new StringBuilder();
        int lines = 20_000; // 20000×20000 = 4 亿单元格 ≈1.6GB，远超 MAX_LCS_CELLS
        for (int i = 0; i < lines; i++) {
            oldB.append("line").append(i).append(" common old\n");
            newB.append("LINE").append(i).append(" changed new\n"); // 每行都变（无公共行，除前缀/后缀边界）
        }
        String diff = LineDiff.unified(oldB.toString(), newB.toString());
        Asserts.assertTrue("diff 输出应完整覆盖双侧内容", diff.length() > lines * 10L);
        Asserts.assertContains("应含删除行", diff, "- line0");
        Asserts.assertContains("应含新增行", diff, "+ LINE0");
        Asserts.assertTrue("不应因 LCS 内存爆炸返回空/抛异常", diff.length() > 0);
    }

    /** 构建噪声折叠（回归护栏）：版本化整包（M059/M061）下嵌套 jar/war 内部仅「构建元数据」差异的文件
     *  （Maven MANIFEST 的 Timestamp、pom.properties 日期注释、MANIFEST.xml 部署清单 md5/size/版本路径）
     *  应折叠为未变——消除「二层嵌套 jar/war 内部文件被误识别为新增/删除」的假阳；
     *  真实内容差异（前端 chunk 逻辑改动）必须保留为差异。 */
    public void testCompute_foldBuildNoiseAcrossVersionedNested() throws Exception {
        String R59 = "BEMP5.0V202301-02-036M059(20260703-1104).zip/";
        String R61 = "BEMP5.0V202301-02-036M061(20260707-1135).zip/";
        // ① jar 内 MANIFEST.MF：仅 Timestamp 行不同 → 折叠
        byte[] mfOld = "Manifest-Version: 1.0\nImplementation-Version: 5.20230102036M.4\nTimestamp: 2026-07-03T03:05:38Z\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] mfNew = "Manifest-Version: 1.0\nImplementation-Version: 5.20230102036M.4\nTimestamp: 2026-07-07T03:35:41Z\n"
                .getBytes(StandardCharsets.UTF_8);
        String mfKey = "bemp-adapter/WEB-INF/lib/bemp-fw-dao-5.20230102036M.4.jar/META-INF/MANIFEST.MF";
        // ② pom.properties：仅日期注释行不同 → 折叠
        byte[] pomOld = "#Fri Jul 03 11:06:08 CST 2026\nversion=5.20230102036M.4\n".getBytes(StandardCharsets.UTF_8);
        byte[] pomNew = "#Tue Jul 07 11:36:11 CST 2026\nversion=5.20230102036M.4\n".getBytes(StandardCharsets.UTF_8);
        String pomKey = "bemp-adapter/WEB-INF/lib/bemp-channel-api-5.20230102036M.1.jar/META-INF/maven/com.h/b/pom.properties";
        // ③ MANIFEST.xml 部署清单：path 版本 + md5/size 变化 → 折叠
        byte[] manOld = ("<application product_version=\"BEMP5.0-adapterV202301.02.036M059(20260703.1104)\">\n"
                + "<file  path=\"BEMP5.0-adapterV202301-02-036M059(20260703-1104)/x.jar\" size = \"123\" md5 = \"aaaa\"  />\n"
                + "</application>\n").getBytes(StandardCharsets.UTF_8);
        byte[] manNew = ("<application product_version=\"BEMP5.0-adapterV202301.02.036M061(20260707.1135)\">\n"
                + "<file  path=\"BEMP5.0-adapterV202301-02-036M061(20260707-1135)/x.jar\" size = \"124\" md5 = \"bbbb\"  />\n"
                + "</application>\n").getBytes(StandardCharsets.UTF_8);
        String manKey = "META-INF/MANIFEST.xml";
        // ④ 真实变化：前端 JS watch 逻辑改动（无版本 token）→ 不折叠
        byte[] jsOld = "chunk();watch:{totalAmt:function(){}};methods:{}\n".getBytes(StandardCharsets.UTF_8);
        byte[] jsNew = "chunk();watch:{};methods:{changeSettleDate:function(){}}\n".getBytes(StandardCharsets.UTF_8);
        String jsOldKey = "bemp-web-5.20230102036M.15.war/banks/q/quoteRebuyInput.14da1892.js";
        String jsNewKey = "bemp-web-5.20230102036M.17.war/banks/q/quoteRebuyInput.b03d20a7.js";
        // ⑤ 纯数字配置差异（port 8080→9090）不是版本噪声 → 不折叠（防误伤）
        byte[] portOld = "server.port=8080\n".getBytes(StandardCharsets.UTF_8);
        byte[] portNew = "server.port=9090\n".getBytes(StandardCharsets.UTF_8);
        String portKey = "conf/application.properties";

        Map<String, LogicalEntry> old = new LinkedHashMap<>();
        Map<String, LogicalEntry> now = new LinkedHashMap<>();
        putFile(old, R59 + mfKey, FileClass.CONFIG, mfOld);
        putFile(now, R61 + mfKey, FileClass.CONFIG, mfNew);
        putFile(old, R59 + pomKey, FileClass.CONFIG, pomOld);
        putFile(now, R61 + pomKey, FileClass.CONFIG, pomNew);
        putFile(old, R59 + manKey, FileClass.CONFIG, manOld);
        putFile(now, R61 + manKey, FileClass.CONFIG, manNew);
        putFile(old, R59 + jsOldKey, FileClass.JS, jsOld);
        putFile(now, R61 + jsNewKey, FileClass.JS, jsNew);
        putFile(old, R59 + portKey, FileClass.CONFIG, portOld);
        putFile(now, R61 + portKey, FileClass.CONFIG, portNew);

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertContains("MANIFEST.MF 时间戳差异→未变", r.get(DiffStatus.UNCHANGED).toString(), R59 + mfKey);
        Asserts.assertFalse("MANIFEST.MF 不应残留删除", r.get(DiffStatus.DELETED).contains(R59 + mfKey));
        Asserts.assertFalse("MANIFEST.MF 不应残留新增", r.get(DiffStatus.ADDED).contains(R61 + mfKey));
        Asserts.assertContains("pom.properties 日期注释差异→未变", r.get(DiffStatus.UNCHANGED).toString(), R59 + pomKey);
        Asserts.assertContains("MANIFEST.xml 部署清单差异→未变", r.get(DiffStatus.UNCHANGED).toString(), R59 + manKey);
        // 真实前端变化：版本等价路径 + 内容不同 → 配对为「修改」（同一逻辑文件 webpack 哈希改名，而非删除+新增）
        Asserts.assertContains("JS 真变化→修改", r.get(DiffStatus.MODIFIED).toString(), R59 + jsOldKey);
        Asserts.assertFalse("JS 真变化不应残留删除", r.get(DiffStatus.DELETED).contains(R59 + jsOldKey));
        Asserts.assertFalse("JS 真变化不应残留新增", r.get(DiffStatus.ADDED).contains(R61 + jsNewKey));
        // 纯数字配置差异：版本等价路径 + 内容不同 → 修改（不是版本噪声，不折叠为未变）
        Asserts.assertContains("port 配置变化→修改", r.get(DiffStatus.MODIFIED).toString(), R59 + portKey);
        Asserts.assertFalse("port 配置变化不应残留删除", r.get(DiffStatus.DELETED).contains(R59 + portKey));
        Asserts.assertFalse("port 配置变化不应残留新增", r.get(DiffStatus.ADDED).contains(R61 + portKey));
        // 改名配对映射：内容层按 MODIFIED 旧 key 必须能反查新侧 key，否则新侧取不到文件而误判「整文件删除」
        Asserts.assertEquals("JS 改名配对→新 key", R61 + jsNewKey, r.newKeyFor(R59 + jsOldKey));
        Asserts.assertEquals("port 改名配对→新 key", R61 + portKey, r.newKeyFor(R59 + portKey));
        Asserts.assertNull("未配对 key 不应返回映射", r.newKeyFor(R59 + mfKey + "_nonexist"));
        // 反向映射：差异树以「新包名」展示 MODIFIED 后，内容层按新 key 必须能反查旧侧条目，否则误判「整文件新增」
        Asserts.assertEquals("JS 反向映射→旧 key", R59 + jsOldKey, r.oldKeyFor(R61 + jsNewKey));
        Asserts.assertEquals("port 反向映射→旧 key", R59 + portKey, r.oldKeyFor(R61 + portKey));
        Asserts.assertNull("未配对新 key 不应返回反向映射", r.oldKeyFor(R61 + jsNewKey + "_nonexist"));
    }

    /** 把字节写入临时文件并登记为快照条目（供构建噪声折叠测试用真实内容做 sha 判定）。 */
    private static void putFile(Map<String, LogicalEntry> m, String key, FileClass fc, byte[] data) throws Exception {
        Path p = Files.createTempFile("bpdiff-", ".bin");
        Files.write(p, data);
        p.toFile().deleteOnExit();
        m.put(key, new LogicalEntry(key, Layer.L0, fc, data.length, shaHex(data), new EntrySource(p.toString(), null)));
    }

    private static String shaHex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(data);
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 构建元数据噪声归一化（展示侧与树状态口径统一的基石）：
     *  pom.properties 仅日期注释/版本号随构建变化 → 归一化后一致（树折叠为未变，展开也应无差异）；
     *  普通 .properties / pom.xml 不应误判为构建元数据；真实业务差异归一化后仍保留。
     * 回归：用户反馈 pom.properties 树置灰但展开对比栏显示日期差异——根因是树用噪声归一化折叠、展开未归一化。
     */
    public void testBuildMetadataNoiseNormalization() throws Exception {
        String a = "#Generated by Maven\n#Tue Aug 05 16:59:00 CST 2026\nversion=5.20230104002M.4\ngroupId=com.hundsun.bemp\n";
        String b = "#Generated by Maven\n#Fri Sep 04 10:30:00 CST 2026\nversion=5.20230104002M.4\ngroupId=com.hundsun.bemp\n";
        String na = new String(DiffEngine.normalizeBuildNoise(a.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String nb = new String(DiffEngine.normalizeBuildNoise(b.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Asserts.assertEquals("日期注释/版本号噪声归一化后应一致", na, nb);

        // 真实业务差异（groupId 不同）归一化后仍保留，不被误抹
        String c = "#Generated by Maven\n#Tue Aug 05 16:59:00 CST 2026\nversion=5.20230104002M.4\ngroupId=com.other\n";
        String nc = new String(DiffEngine.normalizeBuildNoise(c.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Asserts.assertFalse("真实业务差异归一化后仍应不同", na.equals(nc));

        // 判定范围：仅 Maven 构建元数据，不误伤普通配置/工程文件
        Asserts.assertTrue("pom.properties 应判定为构建元数据",
                DiffEngine.isBuildMetadataKey("lib/a.jar!/META-INF/maven/com.x/y/pom.properties"));
        Asserts.assertTrue("MANIFEST.MF 应判定为构建元数据",
                DiffEngine.isBuildMetadataKey("lib/a.jar!/META-INF/MANIFEST.MF"));
        Asserts.assertTrue("部署清单 MANIFEST.xml 应判定为构建元数据",
                DiffEngine.isBuildMetadataKey("lib/a.jar!/META-INF/MANIFEST.xml"));
        Asserts.assertFalse("普通 properties 不应判定为构建元数据",
                DiffEngine.isBuildMetadataKey("conf/app.properties"));
        Asserts.assertFalse("pom.xml 不应判定为构建元数据",
                DiffEngine.isBuildMetadataKey("META-INF/maven/com.x/y/pom.xml"));
    }

    /**
     * 回归：pom.properties 中 version=5.10.20230104002M.1 这类「点分前缀 + BEMP 构建号」仅随构建变化时，
     * 归一化应把整值折叠为占位符，不得残留 "5.V" 之类伪值碎片（展开对比栏不清真值）。
     */
    public void testBuildNoise_collapsesVersionValueFragment() throws Exception {
        String a = "#Generated by Maven\n#Thu Sep 03 16:35:25 CST 2026\nversion=5.20230104002M.1\ngroupId=com.hundsun.bemp\nartifactId=bemp-adapter-as\n";
        String b = "#Generated by Maven\n#Fri Sep 04 10:30:00 CST 2026\nversion=5.20230104002M.2\ngroupId=com.hundsun.bemp\nartifactId=bemp-adapter-as\n";
        String na = new String(DiffEngine.normalizeBuildNoise(a.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String nb = new String(DiffEngine.normalizeBuildNoise(b.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        // 日期注释（#Thu…#Fri）与 version 构建号均随构建变化 → 归一化后一致
        Asserts.assertEquals("仅日期注释/构建号差异应归一化一致", na, nb);
        // 关键：不得残留 "5." 前缀碎片拼接成的伪版本 "5.V"
        Asserts.assertNotContains("不得残留 value 伪碎片（5.V）", na, "5.V");
    }

    /**
     * 回归（T00875 验证失败）：版本化外层交付 zip（M059/M061）配对后的嵌套内部纯文本文件
     * （如 …/scripts/install.sh）内容一致但仅行尾 CRLF/LF 差异时，应判「未变」而非「修改」。
     * 此前 alignSubFiles 只按原始 sha 硬比较，未做行尾归一化，导致整个嵌套 zip 内的文本文件被误判成片修改。
     */
    public void testCompute_lineEndingOnlyNestedSubFileIsUnchanged() throws Exception {
        // 嵌套 zip 内部脚本：内容一致（同一 body），旧侧 CRLF、新侧 LF → sha 不同但规范化文本相同
        java.nio.file.Path oF = java.nio.file.Files.createTempFile("bempdiff-ozip-", ".sh.txt");
        java.nio.file.Path nF = java.nio.file.Files.createTempFile("bempdiff-nzip-", ".sh.txt");
        String body = "#!/bin/sh\nset -e\necho install done\n";
        // createTempFile 会真实写盘；此处把系统临时文件名当作「物理解包后的磁盘路径」供 tryReadFlatDisk 读取，
        // 与 testCompute_lineEndingOnlyShIsUnchanged 同模式。
        java.nio.file.Files.write(oF, body.getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.write(nF, body.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        // sha 必须不同才能触发 alignSubFiles 的归一化兜底（真实场景下 CRLF/LF 的 sha 本就不同）
        java.util.Map<String, LogicalEntry> old = new LinkedHashMap<>();
        java.util.Map<String, LogicalEntry> now = new LinkedHashMap<>();
        final String oz = "BEMP5.0V202301-02-036M059(20260703-1104).zip";
        final String nz = "BEMP5.0V202301-02-036M061(20260707-1135).zip";
        final String os = oz + "!/scripts/install.sh";
        final String ns = nz + "!/scripts/install.sh";
        // 外层版本化 zip 容器（作为 ARCHIVE 条目），sha 不同确保跨版本配对路径被走到
        old.put(oz, new LogicalEntry(oz, Layer.L0, FileClass.ARCHIVE, 0, "sha_o_zip", new EntrySource("x", null)));
        old.put(os, new LogicalEntry(os, Layer.L0, FileClass.CONFIG, java.nio.file.Files.size(oF),
                "sha_o_crlf", new EntrySource(oF.toString(), null)));
        now.put(nz, new LogicalEntry(nz, Layer.L0, FileClass.ARCHIVE, 0, "sha_n_zip", new EntrySource("x", null)));
        now.put(ns, new LogicalEntry(ns, Layer.L0, FileClass.CONFIG, java.nio.file.Files.size(nF),
                "sha_n_lf", new EntrySource(nF.toString(), null)));

        DiffResult r = new DiffEngine().compute(snap(old), snap(now));

        // 嵌套内部 install.sh：仅行尾差异 → 未变，不得进修改/删除/新增
        Asserts.assertContains("嵌套内部仅行尾差异应判未变", r.get(DiffStatus.UNCHANGED).toString(), os);
        Asserts.assertFalse("嵌套内部仅行尾差异不应判修改", r.get(DiffStatus.MODIFIED).contains(os));
        Asserts.assertFalse("嵌套内部仅行尾差异不应残留删除", r.get(DiffStatus.DELETED).contains(os));
        Asserts.assertFalse("嵌套内部仅行尾差异不应残留新增", r.get(DiffStatus.ADDED).contains(ns));

        // 反向对照：嵌套内部文件内容确不同（多一行）→ 应判修改（归一化兜底不得误伤真实差异）
        java.nio.file.Files.write(nF, (body + "echo extra\n").getBytes(StandardCharsets.UTF_8));
        java.util.Map<String, LogicalEntry> now2 = new LinkedHashMap<>();
        now2.put(nz, new LogicalEntry(nz, Layer.L0, FileClass.ARCHIVE, 0, "sha_n_zip", new EntrySource("x", null)));
        now2.put(ns, new LogicalEntry(ns, Layer.L0, FileClass.CONFIG, java.nio.file.Files.size(nF),
                "sha_n_diff", new EntrySource(nF.toString(), null)));
        DiffResult r2 = new DiffEngine().compute(snap(old), snap(now2));
        Asserts.assertFalse("嵌套内部内容确不同不应误判未变", r2.get(DiffStatus.UNCHANGED).contains(os));
        Asserts.assertContains("嵌套内部内容确不同应判修改", r2.get(DiffStatus.MODIFIED).toString(), os);
    }

    /**
     * 回归（T00875 二次验证失败真实根因）：BEMP 交付件中 web 组件为「zip + 同名解压目录」双形态并存，
     * 四个 install.sh 内容字节全同（sha 相同）。内容键候选桶内同时出现 zip 版与目录版两个版本等价候选，
     * 唯一性守卫放弃折叠，随后被按路径配对为 MODIFIED——内容一致的文件成片误报。
     * 修复后应按「同版本骨架」优先配对（zip 配 zip、目录配目录），各自折叠为未变。
     */
    public void testCompute_dualFormSameContentFoldsToUnchanged() {
        java.util.Map<String, LogicalEntry> old = new LinkedHashMap<>();
        java.util.Map<String, LogicalEntry> now = new LinkedHashMap<>();
        final String ozZip = "BEMP5.0-webV202301-02-036M059(20260703-1104).zip/scripts/install.sh";
        final String ozDir = "BEMP5.0-webV202301-02-036M059(20260703-1104)/scripts/install.sh";
        final String nzZip = "BEMP5.0-webV202301-02-036M061(20260707-1135).zip/scripts/install.sh";
        final String nzDir = "BEMP5.0-webV202301-02-036M061(20260707-1135)/scripts/install.sh";
        // 四条同 sha（内容字节全同）——真实场景 E:\testC 实测两侧 install.sh sha 相同仍被判修改
        old.put(ozZip, le(ozZip, Layer.L0, FileClass.CONFIG, "shaSame"));
        old.put(ozDir, le(ozDir, Layer.L0, FileClass.CONFIG, "shaSame"));
        now.put(nzZip, le(nzZip, Layer.L0, FileClass.CONFIG, "shaSame"));
        now.put(nzDir, le(nzDir, Layer.L0, FileClass.CONFIG, "shaSame"));
        DiffResult r = new DiffEngine().compute(snap(old), snap(now));
        Asserts.assertFalse("双形态 zip 版内容一致不应判修改", r.get(DiffStatus.MODIFIED).contains(ozZip));
        Asserts.assertFalse("双形态目录版内容一致不应判修改", r.get(DiffStatus.MODIFIED).contains(ozDir));
        Asserts.assertFalse("双形态 zip 版不应残留删除", r.get(DiffStatus.DELETED).contains(ozZip));
        Asserts.assertFalse("双形态目录版不应残留删除", r.get(DiffStatus.DELETED).contains(ozDir));
        Asserts.assertFalse("双形态 zip 新版不应残留新增", r.get(DiffStatus.ADDED).contains(nzZip));
        Asserts.assertFalse("双形态目录新版不应残留新增", r.get(DiffStatus.ADDED).contains(nzDir));
    }
}

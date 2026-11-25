package com.bempdiff.test;

import com.bempdiff.Main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link Main#main(String[])} CLI 子命令端到端单测：
 * 用 {@link TestFixtures} 造两个可解析的小版本包（app-1.0.0.war / app-1.0.1.war），
 * 依次驱动 inspect / compare / compare-folders / folderdiff / decompile / report /
 * export / diff-jars / ai 子命令，断言其副作用（报告文件、导出目录、AI 回放 prompt 落地）。
 *
 * <p>红线：不触发未知子命令与参数不足分支（{@code System.exit(2)} 会杀死 TestRunner 进程），
 * 不测 {@code server} 子命令（长驻监听）；AI 一律走离线 MockAiAnalyzer（不传 apikey、
 * 且用受控 {@code --aiconfig} 避免读取开发者主目录中的真实配置）。</p>
 */
public final class MainCliTest {

    // ---- 不可变夹具字节（跨用例只读缓存，避免重复起 javac）----
    private static byte[] oldWarBytes;
    private static byte[] newWarBytes;
    private static Path cfrJarResolved;
    private static boolean cfrJarProbed;

    private static final String SRC_REMOVED = """
            package com.internal;
            public class Removed {
                public void legacyOnly() {}
            }
            """;

    private static final String SRC_ADDITION = """
            package com.internal;
            public class Addition {
                public int brandNew() { return 100; }
            }
            """;

    private static final String SRC_B_V2 = """
            package com.internal;
            public class B {
                public String hello() { return "hi v2"; }
                public int extra() { return 7; }
            }
            """;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 惰性构造两个受控版本包字节（只读缓存）。 */
    private static synchronized void ensureFixtures() throws IOException {
        if (oldWarBytes != null) return;

        Map<String, byte[]> o = new LinkedHashMap<>();
        o.put("WEB-INF/classes/com/internal/A.class",
                TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        o.put("WEB-INF/classes/com/other/X.class",
                TestFixtures.compileClass("com.other.X", TestFixtures.SRC_X));
        o.put("WEB-INF/classes/com/internal/Removed.class",
                TestFixtures.compileClass("com.internal.Removed", SRC_REMOVED));
        o.put("WEB-INF/classes/conf/legacy.xml", utf8("<root>\n  <v>1</v>\n</root>\n"));
        o.put("WEB-INF/classes/conf/dropped.xml", utf8("<dropped>\n  <a>1</a>\n</dropped>\n"));
        o.put("webapp/app.js", utf8("var total = 1;\nfunction calc() { return total; }\n"));
        o.put("webapp/index.html", utf8("<html>\n  <body>v1</body>\n</html>\n"));
        o.put("WEB-INF/web.xml", utf8("<web-app>\n  <param>one</param>\n</web-app>\n"));
        Map<String, byte[]> innerOld = new LinkedHashMap<>();
        innerOld.put("com/internal/B.class", TestFixtures.compileClass("com.internal.B", TestFixtures.SRC_B));
        o.put("WEB-INF/lib/internal-core.jar", TestFixtures.makeZip(innerOld));
        Map<String, byte[]> tp = new LinkedHashMap<>();
        tp.put("org/apache/T.class", TestFixtures.compileClass("org.apache.T", TestFixtures.SRC_T));
        byte[] thirdParty = TestFixtures.makeZip(tp);
        o.put("WEB-INF/lib/third-party.jar", thirdParty);

        Map<String, byte[]> n = new LinkedHashMap<>();
        n.put("WEB-INF/classes/com/internal/A.class",
                TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V2));
        n.put("WEB-INF/classes/com/other/X.class",
                TestFixtures.compileClass("com.other.X", TestFixtures.SRC_X));
        n.put("WEB-INF/classes/com/internal/Addition.class",
                TestFixtures.compileClass("com.internal.Addition", SRC_ADDITION));
        n.put("WEB-INF/classes/conf/legacy.xml", utf8("<root>\n  <v>2</v>\n</root>\n"));
        // conf/dropped.xml 在新包中删除 → DELETED 文本候选 + 破坏性清单
        n.put("webapp/app.js", utf8("var total = 2;\nfunction calc() { return total * 2; }\n"));
        n.put("webapp/index.html", utf8("<html>\n  <body>v2</body>\n</html>\n"));
        n.put("WEB-INF/web.xml", utf8("<web-app>\n  <param>two</param>\n</web-app>\n"));
        Map<String, byte[]> innerNew = new LinkedHashMap<>();
        innerNew.put("com/internal/B.class", TestFixtures.compileClass("com.internal.B", SRC_B_V2));
        n.put("WEB-INF/lib/internal-core.jar", TestFixtures.makeZip(innerNew));
        n.put("WEB-INF/lib/third-party.jar", thirdParty); // 字节完全一致 → UNCHANGED L2

        oldWarBytes = TestFixtures.makeWar(o, "1.0.0");
        newWarBytes = TestFixtures.makeWar(n, "1.0.1");
    }

    /** 一次比较的输入包对与其所在临时目录。 */
    private static final class Pair {
        final Path dir;
        final Path oldP;
        final Path newP;

        Pair(Path dir, Path oldP, Path newP) {
            this.dir = dir;
            this.oldP = oldP;
            this.newP = newP;
        }
    }

    /** 把夹具字节写入独立临时目录（文件名符合 PackageVersion 可识别的「同名不同版本」格式）。 */
    private static Pair pair(String tag) throws IOException {
        ensureFixtures();
        Path dir = Files.createTempDirectory("bdfmain-cli-" + tag);
        Path oldP = dir.resolve("bemp-app-1.0.0.war");
        Path newP = dir.resolve("bemp-app-1.0.1.war");
        Files.write(oldP, oldWarBytes);
        Files.write(newP, newWarBytes);
        return new Pair(dir, oldP, newP);
    }

    /** 从运行时 classpath 定位 cfr.jar（找不到则不传 --cfr，Decompiler 仍走进程内 CFR/javap）。 */
    private static synchronized Path cfrJar() {
        if (cfrJarProbed) return cfrJarResolved;
        cfrJarProbed = true;
        String sep = System.getProperty("path.separator", ";");
        for (String e : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(sep))) {
            if (e.toLowerCase().endsWith("cfr.jar") && Files.isRegularFile(Paths.get(e))) {
                cfrJarResolved = Paths.get(e);
                return cfrJarResolved;
            }
        }
        try {
            java.net.URL u = Class.forName("org.benf.cfr.reader.api.CfrDriver")
                    .getProtectionDomain().getCodeSource().getLocation();
            if (u != null && "file".equalsIgnoreCase(u.getProtocol())) {
                cfrJarResolved = Paths.get(u.toURI());
            }
        } catch (Throwable ignored) {
            cfrJarResolved = null;
        }
        return cfrJarResolved;
    }

    /** 组装子命令参数：首元素为子命令名，其后为位置/选项参数，自动附加 --cfr。 */
    private static String[] cli(String cmd, String... rest) {
        List<String> all = new ArrayList<>();
        all.add(cmd);
        java.util.Collections.addAll(all, rest);
        Path c = cfrJar();
        if (c != null) {
            all.add("--cfr");
            all.add(c.toString());
        }
        return all.toArray(new String[0]);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响断言结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static long fileCount(Path dir) throws IOException {
        if (!Files.exists(dir)) return -1;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private static void assertNoThrow(String what, RunnableNoException r) throws Exception {
        try {
            r.run();
        } catch (Exception e) {
            Asserts.fail(what + " 不应抛异常: " + e);
        }
    }

    private interface RunnableNoException {
        void run() throws Exception;
    }

    // ---------------- inspect ----------------

    public void testInspectSubcommand() throws Exception {
        Pair p = pair("inspect");
        try {
            assertNoThrow("inspect", () -> Main.main(cli("inspect", p.oldP.toString())));
            Asserts.assertTrue("包文件应仍可读", Files.isReadable(p.oldP));
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- compare ----------------

    public void testCompareSubcommand() throws Exception {
        Pair p = pair("compare");
        try {
            assertNoThrow("compare", () -> Main.main(cli("compare",
                    p.oldP.toString(), p.newP.toString(), "--expand-all")));
        } finally {
            deleteTree(p.dir);
        }
    }

    /** 同名不同版本反序传入时应走 PackageVersion 智能排序分支（不抛、不改输入文件）。 */
    public void testCompareAutoReordersByVersion() throws Exception {
        Pair p = pair("compare-reorder");
        try {
            assertNoThrow("compare 反序", () -> Main.main(cli("compare",
                    p.newP.toString(), p.oldP.toString())));
            Asserts.assertEquals("老包字节不应被改写", (long) oldWarBytes.length, Files.size(p.oldP));
            Asserts.assertEquals("新包字节不应被改写", (long) newWarBytes.length, Files.size(p.newP));
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- compare-folders / folderdiff ----------------

    /** 造一对存在 left-only / right-only / 内容不同 / 相同 的目录。 */
    private static Path[] folderPair(String tag) throws IOException {
        Path base = Files.createTempDirectory("bdfmain-folders-" + tag);
        Path left = Files.createDirectory(base.resolve("left"));
        Path right = Files.createDirectory(base.resolve("right"));
        Files.writeString(left.resolve("only-left.txt"), "left side only\n");
        Files.writeString(right.resolve("only-right.txt"), "right side only\n");
        Files.createDirectories(left.resolve("src"));
        Files.createDirectories(right.resolve("src"));
        Files.writeString(left.resolve("src/app.js"), "var a = 1;\nvar b = 2;\n");
        Files.writeString(right.resolve("src/app.js"), "var a = 1;\nvar b = 3;\n");
        Files.writeString(left.resolve("keep.md"), "# keep\n");
        Files.writeString(right.resolve("keep.md"), "# keep\n");
        return new Path[]{base, left, right};
    }

    public void testCompareFoldersSubcommand() throws Exception {
        Path[] f = folderPair("cmp-folders");
        try {
            assertNoThrow("compare-folders", () -> Main.main(cli("compare-folders",
                    f[1].toString(), f[2].toString(), "--max-depth", "5")));
        } finally {
            deleteTree(f[0]);
        }
    }

    public void testFolderDiffWithReport() throws Exception {
        Path[] f = folderPair("folderdiff");
        // FolderReport.writeToFile 用 Files.writeString 直接落盘（不建父目录），故写在已存在的 base 根下
        Path md = f[0].resolve("folder-report.md");
        try {
            assertNoThrow("folderdiff", () -> Main.main(cli("folderdiff",
                    f[1].toString(), f[2].toString(), "--report", md.toString(), "--top-k", "3")));
            Asserts.assertTrue("folderdiff 报告应生成: " + md, Files.isRegularFile(md));
            String s = read(md);
            Asserts.assertContains("应含文件夹对比报告标题", s, "# 文件夹对比报告");
            Asserts.assertContains("应含差异汇总章节", s, "## 一、差异汇总");
            Asserts.assertContains("应含差异树章节", s, "## 二、差异树（展开视图）");
            Asserts.assertContains("汇总应体现仅左侧文件", s, "only-left.txt");
        } finally {
            deleteTree(f[0]);
        }
    }

    /** 不带 --report 时 folderdiff 走 reportMd==null 分支。 */
    public void testFolderDiffWithoutReport() throws Exception {
        Path[] f = folderPair("folderdiff-noreport");
        try {
            assertNoThrow("folderdiff 无报告", () -> Main.main(cli("folderdiff",
                    f[1].toString(), f[2].toString())));
        } finally {
            deleteTree(f[0]);
        }
    }

    // ---------------- decompile ----------------

    public void testDecompileSubcommand() throws Exception {
        Pair p = pair("decompile");
        try {
            assertNoThrow("decompile", () -> Main.main(cli("decompile",
                    p.oldP.toString(), p.newP.toString(), "--top-k", "3")));
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- report ----------------

    public void testReportSubcommandWritesMarkdown() throws Exception {
        Pair p = pair("report");
        Path md = p.dir.resolve("out/report.md");
        try {
            assertNoThrow("report", () -> Main.main(cli("report",
                    p.oldP.toString(), p.newP.toString(), "--out", md.toString(), "--top-k", "3")));
            Asserts.assertTrue("报告应生成: " + md, Files.isRegularFile(md));
            String s = read(md);
            Asserts.assertContains("应含差异统计章节", s, "## 一、差异统计");
            Asserts.assertContains("应含差异文件树章节", s, "## 二、差异文件树");
            Asserts.assertContains("应含反编译源码章节", s, "## 三、反编译源码级差异");
            Asserts.assertContains("应含文本类文件章节", s, "## 四、文本类文件内容差异");
            Asserts.assertContains("应含差异 JAR 章节", s, "## 五、差异依赖 JAR 内部源码对比");
            Asserts.assertContains("应含破坏性变更章节", s, "## 六、破坏性变更清单");
            Asserts.assertContains("应列出删除的配置文件", s, "WEB-INF/classes/conf/dropped.xml");
            Asserts.assertNotContains("无 --ai 不应有 AI 章节", s, "AI 智能分析");
        } finally {
            deleteTree(p.dir);
        }
    }

    /** --ai 且无 API Key：应离线降级为 MockAiAnalyzer（不触网），AI 章节与回放 prompt 均落地。 */
    public void testReportWithAiOfflineMock() throws Exception {
        Pair p = pair("report-ai");
        Path md = p.dir.resolve("ai-report.md");
        Path replay = p.dir.resolve("replay");
        Path aiCfg = p.dir.resolve("ai-config.properties");
        // 受控配置文件不含 aiApiKey → 强制走 MockAiAnalyzer，绝不读取开发者主目录真实 Key
        Files.writeString(aiCfg, "aiProvider=openai\naiBaseUrl=https://api.invalid.test/v1\n"
                + "aiModel=mock-model\nstageBTopK=2\n", StandardCharsets.UTF_8);
        try {
            assertNoThrow("report --ai", () -> Main.main(cli("report",
                    p.oldP.toString(), p.newP.toString(), "--out", md.toString(),
                    "--top-k", "2", "--ai", "--aiconfig", aiCfg.toString(),
                    "--replay", replay.toString())));
            Asserts.assertTrue("AI 报告应生成: " + md, Files.isRegularFile(md));
            String s = read(md);
            Asserts.assertContains("应含 AI 智能分析章节", s, "AI 智能分析");
            Asserts.assertContains("AI 章节应含阶段A 概览", s, "阶段A");
            Asserts.assertContains("应落盘审计摘要章节", s, "审计摘要");
            Asserts.assertContains("离线 Mock 的审计口径应为离线兜底", s, "离线兜底");
            Asserts.assertNotContains("离线 Mock 不得声称已接入真实模型", s, "已接入真实模型");
            Path stageA = replay.resolve("stageA.prompt.txt");
            Asserts.assertTrue("离线回放应写出 stageA.prompt.txt: " + stageA, Files.isRegularFile(stageA));
            Asserts.assertContains("prompt 应含差异概览", read(stageA), "差异");
            Asserts.assertTrue("应写出至少一个 stageB prompt", fileCount(replay) >= 2);
        } finally {
            deleteTree(p.dir);
        }
    }

    /** --ai 且带 --project：项目级上下文分支（打印「已启用」并写入报告）。 */
    public void testReportWithAiAndProjectContext() throws Exception {
        Pair p = pair("report-ai-proj");
        Path md = p.dir.resolve("proj-report.md");
        Path replay = p.dir.resolve("replay");
        Path aiCfg = p.dir.resolve("cfg.properties");
        Path proj = Files.createDirectory(p.dir.resolve("proj"));
        Files.writeString(aiCfg, "aiProvider=openai\nstageBTopK=1\n", StandardCharsets.UTF_8);
        Files.writeString(proj.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.t</groupId>"
                        + "<artifactId>bemp-demo</artifactId><version>1.0.0</version></project>\n",
                StandardCharsets.UTF_8);
        try {
            assertNoThrow("report --ai --project", () -> Main.main(cli("report",
                    p.oldP.toString(), p.newP.toString(), "--out", md.toString(),
                    "--top-k", "2", "--ai", "--aiconfig", aiCfg.toString(),
                    "--replay", replay.toString(), "--project", proj.toString())));
            Asserts.assertTrue("带上下文的报告应生成", Files.isRegularFile(md));
            String s = read(md);
            Asserts.assertContains("应含 AI 章节", s, "AI 智能分析");
            Asserts.assertContains("应含项目级上下文章节", s, "项目级上下文");
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- export ----------------

    public void testExportSubcommandWritesAssets() throws Exception {
        Pair p = pair("export");
        Path outDir = p.dir.resolve("export-root");
        try {
            assertNoThrow("export", () -> Main.main(cli("export",
                    p.oldP.toString(), p.newP.toString(), "--out", outDir.toString(), "--top-k", "3")));
            Asserts.assertTrue("导出目录应生成: " + outDir, Files.isDirectory(outDir));
            long n = fileCount(outDir);
            Asserts.assertTrue("导出目录应含资产文件, 实际=" + n, n > 0);
            try (Stream<Path> s = Files.walk(outDir)) {
                boolean anyZip = s.filter(Files::isRegularFile)
                        .anyMatch(x -> x.getFileName().toString().endsWith(".zip"));
                Asserts.assertTrue("应含反编译源码 zip 导出: " + outDir, anyZip);
            }
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- diff-jars ----------------

    public void testDiffJarsSubcommand() throws Exception {
        Pair p = pair("diff-jars");
        Path md = p.dir.resolve("libjar-report.md");
        try {
            assertNoThrow("diff-jars", () -> Main.main(cli("diff-jars",
                    p.oldP.toString(), p.newP.toString(), "--out", md.toString(), "--top-k", "2")));
            Asserts.assertTrue("差异 JAR 报告应生成: " + md, Files.isRegularFile(md));
            String s = read(md);
            Asserts.assertContains("应含差异 JAR 章节", s, "差异依赖 JAR 内部源码对比");
            Asserts.assertContains("应识别出内部业务 jar", s, "internal-core.jar");
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- ai ----------------

    public void testAiSubcommandOfflineMock() throws Exception {
        Pair p = pair("ai");
        Path replay = p.dir.resolve("ai-replay");
        try {
            assertNoThrow("ai", () -> Main.main(cli("ai",
                    p.oldP.toString(), p.newP.toString(), "--top-k", "2",
                    "--replay", replay.toString())));
            Asserts.assertTrue("回放目录应生成: " + replay, Files.isDirectory(replay));
            Path stageA = replay.resolve("stageA.prompt.txt");
            Asserts.assertTrue("stageA prompt 应落地", Files.isRegularFile(stageA));
            Asserts.assertTrue("stageB prompt 应至少落地一个: " + fileCount(replay),
                    fileCount(replay) >= 2);
        } finally {
            deleteTree(p.dir);
        }
    }

    /** --replay 已给定时不应使用 ~/home 默认回放目录（离线且可清理）。 */
    public void testAiWithProjectContextOffline() throws Exception {
        Pair p = pair("ai-proj");
        Path replay = p.dir.resolve("replay2");
        Path proj = Files.createDirectory(p.dir.resolve("proj2"));
        Files.writeString(proj.resolve("package.json"), "{\"name\":\"bemp-web\",\"version\":\"1.0.0\"}\n",
                StandardCharsets.UTF_8);
        try {
            assertNoThrow("ai --project", () -> Main.main(cli("ai",
                    p.oldP.toString(), p.newP.toString(), "--top-k", "1",
                    "--replay", replay.toString(), "--project", proj.toString())));
            Asserts.assertTrue("stageA prompt 应落地", Files.isRegularFile(replay.resolve("stageA.prompt.txt")));
        } finally {
            deleteTree(p.dir);
        }
    }

    /** --expand-all 与 --top-k 组合：main 顶部 flag 解析分支。 */
    public void testReportWithExpandAllAndTopK() throws Exception {
        Pair p = pair("report-flags");
        Path md = p.dir.resolve("flags-report.md");
        try {
            assertNoThrow("report --expand-all", () -> Main.main(cli("report",
                    p.oldP.toString(), p.newP.toString(), "--expand-all",
                    "--top-k", "1", "--out", md.toString())));
            Asserts.assertTrue("报告应生成", Files.isRegularFile(md));
            Asserts.assertContains("标题应体现 Top-K=1", read(md), "Top-1");
        } finally {
            deleteTree(p.dir);
        }
    }

    // ---------------- ci（FR-005） ----------------
    // ci 用 Main.cliMain 断言退出码（main 会 System.exit 杀死 TestRunner 进程）。

    /** 差异对 + --json：报告与 JSON 落地，hasDiff=true，退出码 0（未启用 --fail-on-diff）。 */
    public void testCiWritesReportAndJson() throws Exception {
        Pair p = pair("ci-json");
        Path md = p.dir.resolve("ci-report.md");
        Path json = p.dir.resolve("out/ci.json");
        try {
            int code = Main.cliMain(cli("ci", "--left", p.oldP.toString(), "--right", p.newP.toString(),
                    "--report", md.toString(), "--json", json.toString(), "--top-k", "2"));
            Asserts.assertEquals("未启用 fail-on-diff 时退出码应为 0", 0, code);
            Asserts.assertTrue("CI 报告应生成: " + md, Files.isRegularFile(md));
            Asserts.assertTrue("JSON 摘要应生成: " + json, Files.isRegularFile(json));
            String s = read(json);
            Asserts.assertContains("hasDiff 应为 true", s, "\"hasDiff\":true");
            Asserts.assertContains("stats 应含 added 字段", s, "\"added\":");
            Asserts.assertContains("差异清单应含 modified 分组", s, "\"modified\":");
            Asserts.assertContains("差异清单应含删除的配置文件", s, "WEB-INF/classes/conf/dropped.xml");
            Asserts.assertFalse("无 --ai 不应出现 ai 段", s.contains("\"ai\":"));
        } finally {
            deleteTree(p.dir);
        }
    }

    /** --fail-on-diff + 差异对：退出码 1（CI 门禁阻断语义）。 */
    public void testCiFailOnDiffReturnsOne() throws Exception {
        Pair p = pair("ci-fail");
        try {
            int code = Main.cliMain(cli("ci", "--left", p.oldP.toString(), "--right", p.newP.toString(),
                    "--fail-on-diff", "--top-k", "1"));
            Asserts.assertEquals("有差异且启用 fail-on-diff 应返回 1", 1, code);
        } finally {
            deleteTree(p.dir);
        }
    }

    /** 同包自比（无差异）：hasDiff=false，--fail-on-diff 下仍退出 0。 */
    public void testCiNoDiffReturnsZero() throws Exception {
        Pair p = pair("ci-nodiff");
        Path json = p.dir.resolve("nodiff.json");
        try {
            int code = Main.cliMain(cli("ci", "--left", p.oldP.toString(), "--right", p.oldP.toString(),
                    "--fail-on-diff", "--json", json.toString(), "--top-k", "1"));
            Asserts.assertEquals("无差异即使 fail-on-diff 也应返回 0", 0, code);
            Asserts.assertContains("hasDiff 应为 false", read(json), "\"hasDiff\":false");
        } finally {
            deleteTree(p.dir);
        }
    }

    /** 缺 --right：返回 2（用法错误），且不得 System.exit 杀死测试进程。 */
    public void testCiMissingArgsReturnsTwo() throws Exception {
        Pair p = pair("ci-badargs");
        try {
            int code = Main.cliMain(new String[]{"ci", "--left", p.oldP.toString()});
            Asserts.assertEquals("参数缺失应返回 2", 2, code);
        } finally {
            deleteTree(p.dir);
        }
    }
}

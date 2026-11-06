package com.bempdiff.test;

import com.bempdiff.Main;
import com.bempdiff.config.AiConfig;
import com.bempdiff.ai.context.ProjectContext;
import com.bempdiff.decompile.Decompiler;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.FolderDiff;
import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.Layer;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.model.EntrySource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link Main} 私有静态方法的反射单测：只覆盖纯逻辑小方法与可离线复现的分支，
 * 绝不触发 {@code System.exit}（requireArgs 仅测合法分支）与 {@code server} 子命令。
 *
 * <p>约定见 bempdiff-test-brief：自研 Asserts/TestRunner，零外部测试库、零外网。</p>
 */
public final class MainReflectTest {

    // ---------- 反射工具 ----------

    /** 取 Main 的私有静态方法并放开访问。 */
    private static Method method(String name, Class<?>... params) throws Exception {
        Method m = Main.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Object call(String name, Class<?>[] params, Object... args) throws Exception {
        try {
            return method(name, params).invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new AssertionError("调用 Main." + name + " 抛出异常: " + c, c == null ? e : c);
        }
    }

    private static final Class<?>[] PATH_BOOL = {Path.class, Path.class, boolean.class};
    private static final Class<?>[] MAP_LAYER = {Map.class, Layer.class};
    private static final Class<?>[] LIST_STR = {List.class};

    // ---------- 临时目录工具 ----------

    private static Path tempDir(String prefix) throws IOException {
        Path d = Files.createTempDirectory(prefix);
        try (Stream<Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().deleteOnExit());
        }
        return d;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 造两个可解析的小版本包（1.0.0 → 1.0.1），含 class 变更、文本配置变更/删除、前端 JS 变更。 */
    private static Path[] warPair(String tag) throws IOException {
        Path dir = tempDir("bdfmain-refl-" + tag);
        Map<String, byte[]> oldE = new LinkedHashMap<>();
        oldE.put("WEB-INF/classes/com/internal/A.class",
                TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        oldE.put("WEB-INF/classes/conf/legacy.xml", utf8("<root>\n  <v>1</v>\n</root>\n"));
        oldE.put("WEB-INF/classes/conf/gone.xml", utf8("<gone>\n  <old>yes</old>\n</gone>\n"));
        oldE.put("WEB-INF/static/app.js", utf8("var a = 1;\nfunction f(){return a;}\n"));
        byte[] oldBytes = TestFixtures.makeWar(oldE, "1.0.0");

        Map<String, byte[]> newE = new LinkedHashMap<>();
        newE.put("WEB-INF/classes/com/internal/A.class",
                TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V2));
        newE.put("WEB-INF/classes/conf/legacy.xml", utf8("<root>\n  <v>2</v>\n</root>\n"));
        // gone.xml 消失 → DELETED 文本候选（buildTextMap 的 oe!=null 分支）
        newE.put("WEB-INF/static/app.js", utf8("var a = 2;\nfunction f(){return a;}\n"));
        byte[] newBytes = TestFixtures.makeWar(newE, "1.0.1");

        Path oldP = dir.resolve("bemp-refl-app-1.0.0.war");
        Path newP = dir.resolve("bemp-refl-app-1.0.1.war");
        Files.write(oldP, oldBytes);
        Files.write(newP, newBytes);
        return new Path[]{oldP, newP};
    }

    private static PackageSnapshot snapshot(String file, LogicalEntry... entries) {
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        for (LogicalEntry e : entries) m.put(e.getKey(), e);
        return new PackageSnapshot(Paths.get(file), com.bempdiff.model.PackageType.WAR, "1.0", m);
    }

    private static LogicalEntry entry(String key, Layer layer, FileClass fc) {
        return new LogicalEntry(key, layer, fc, 1L, "sha-" + key, EntrySource.memoryBacked(new byte[]{1, 2}));
    }

    // ---------- layerCount ----------

    public void testLayerCountAllLayers() throws Exception {
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("a", entry("a", Layer.L0, FileClass.CONFIG));
        m.put("b", entry("b", Layer.L1, FileClass.CLASS));
        m.put("c", entry("c", Layer.L1, FileClass.CLASS));
        m.put("d", entry("d", Layer.L2, FileClass.JAR));
        Asserts.assertEquals("L0 计数", 1, call("layerCount", MAP_LAYER, m, Layer.L0));
        Asserts.assertEquals("L1 计数", 2, call("layerCount", MAP_LAYER, m, Layer.L1));
        Asserts.assertEquals("L2 计数", 1, call("layerCount", MAP_LAYER, m, Layer.L2));
        Asserts.assertEquals("空 map 计数", 0, call("layerCount", MAP_LAYER,
                new LinkedHashMap<String, LogicalEntry>(), Layer.L1));
    }

    // ---------- repeat ----------

    public void testRepeatBoundaries() throws Exception {
        Asserts.assertEquals("n=0 返回空串", "", call("repeat", new Class[]{String.class, int.class}, "ab", 0));
        Asserts.assertEquals("n=1", "ab", call("repeat", new Class[]{String.class, int.class}, "ab", 1));
        Asserts.assertEquals("n=3", "ababab", call("repeat", new Class[]{String.class, int.class}, "ab", 3));
        Asserts.assertEquals("负数不追加", "", call("repeat", new Class[]{String.class, int.class}, "x", -2));
        Asserts.assertEquals("空串重复仍空", "", call("repeat", new Class[]{String.class, int.class}, "", 5));
    }

    // ---------- countFiles ----------

    public void testCountFilesBranches() throws Exception {
        Path missing = tempDir("bdfmain-cf-missing").resolve("nope");
        Asserts.assertEquals("不存在的目录计 0", 0L, call("countFiles", new Class[]{Path.class}, missing));

        Path empty = tempDir("bdfmain-cf-empty");
        Asserts.assertEquals("空目录计 0", 0L, call("countFiles", new Class[]{Path.class}, empty));

        Path root = tempDir("bdfmain-cf-root");
        Files.writeString(root.resolve("one.txt"), "1");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("two.txt"), "2");
        Files.writeString(sub.resolve("three.txt"), "3");
        Asserts.assertEquals("递归计数含子目录 3 个普通文件", 3L,
                call("countFiles", new Class[]{Path.class}, root));
    }

    // ---------- outPath / reportOutPath ----------

    public void testOutPathWithAndWithoutFlag() throws Exception {
        List<String> with = new ArrayList<>(List.of("export", "o", "n", "--out", "/tmp/xx/dir"));
        Asserts.assertEquals("命中 flag 取其后值", Paths.get("/tmp/xx/dir"),
                call("outPath", new Class[]{List.class, String.class, String.class}, with, "--out", "def.zip"));
        List<String> without = new ArrayList<>(List.of("export", "o", "n"));
        Asserts.assertEquals("未命中 flag 用默认相对名", Paths.get("def.zip"),
                call("outPath", new Class[]{List.class, String.class, String.class}, without, "--out", "def.zip"));
    }

    public void testReportOutPathAliases() throws Exception {
        Class<?>[] sig = {List.class, String.class};
        List<String> outFlag = new ArrayList<>(List.of("report", "o", "n", "--out", "a.md"));
        Asserts.assertEquals("--out 优先", Paths.get("a.md"), call("reportOutPath", sig, outFlag, "d.md"));

        List<String> reportFlag = new ArrayList<>(List.of("folderdiff", "l", "r", "--report", "b.md"));
        Asserts.assertEquals("--report 别名生效", Paths.get("b.md"), call("reportOutPath", sig, reportFlag, "d.md"));

        List<String> none = new ArrayList<>(List.of("report", "o", "n"));
        Asserts.assertEquals("两者皆无回落默认名", Paths.get("d.md"), call("reportOutPath", sig, none, "d.md"));

        List<String> both = new ArrayList<>(List.of("report", "o", "n", "--out", "win.md", "--report", "lose.md"));
        Asserts.assertEquals("--out 先于 --report 命中", Paths.get("win.md"), call("reportOutPath", sig, both, "d.md"));
    }

    // ---------- fileClassOfKey ----------

    public void testFileClassOfKeyThreeBranches() throws Exception {
        PackageSnapshot oldSnap = snapshot("old.war",
                entry("WEB-INF/classes/com/internal/A.class", Layer.L1, FileClass.CLASS),
                entry("WEB-INF/classes/conf/gone.xml", Layer.L0, FileClass.CONFIG));
        PackageSnapshot newSnap = snapshot("new.war",
                entry("WEB-INF/classes/com/internal/A.class", Layer.L1, FileClass.CLASS),
                entry("WEB-INF/static/new-app.css", Layer.L0, FileClass.CSS));
        Class<?>[] sig = {String.class, PackageSnapshot.class, PackageSnapshot.class};
        Asserts.assertEquals("老包命中优先取老侧分类", FileClass.CONFIG,
                call("fileClassOfKey", sig, "WEB-INF/classes/conf/gone.xml", oldSnap, newSnap));
        Asserts.assertEquals("老包未命中回退新侧分类", FileClass.CSS,
                call("fileClassOfKey", sig, "WEB-INF/static/new-app.css", oldSnap, newSnap));
        Asserts.assertEquals("两侧皆无兜底 CLASS", FileClass.CLASS,
                call("fileClassOfKey", sig, "no/such/key.class", oldSnap, newSnap));
    }

    // ---------- findJava ----------

    public void testFindJavaReturnsUsablePath() throws Exception {
        String s = (String) call("findJava", new Class<?>[]{});
        Asserts.assertNotNull("findJava 不应返回 null", s);
        Asserts.assertTrue("返回值非空: " + s, !s.isEmpty());
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Asserts.assertTrue("有 JAVA_HOME 时应指向 bin 下的 java 可执行文件: " + s,
                    s.contains("bin") && (s.endsWith("java.exe") || s.endsWith("java")));
        } else {
            Asserts.assertEquals("无 JAVA_HOME 回落裸命令", "java", s);
        }
    }

    // ---------- loadAiConfig ----------

    public void testLoadAiConfigMissingFileUsesDefaults() throws Exception {
        List<String> a = new ArrayList<>(List.of("report", "o", "n", "--aiconfig",
                tempDir("bdfmain-aicfg").resolve("absent.properties").toString()));
        AiConfig c = (AiConfig) call("loadAiConfig", LIST_STR, a);
        Asserts.assertEquals("默认 provider", "openai", c.getProvider());
        Asserts.assertEquals("默认 model", "gpt-4o", c.getModel());
        Asserts.assertEquals("默认无 Key", "", c.getApiKey());
        Asserts.assertFalse("无 Key 时未启用", c.isEnabled());
        Asserts.assertEquals("默认 stageBTopK", 15, c.getStageBTopK());
    }

    public void testLoadAiConfigFromFile() throws Exception {
        Path dir = tempDir("bdfmain-aicfg-load");
        Path cfg = dir.resolve("ui-config.properties");
        Files.writeString(cfg, "aiProvider=qwen\naiBaseUrl=https://local.test/v1\n"
                + "aiModel=qwen-max\nstageBTopK=7\ncostGateWarnTokens=1234\n"
                + "httpProxy=http://p:1\nhttpsProxy=http://s:2\nblockPrivateEndpoints=true\n",
                StandardCharsets.UTF_8);
        List<String> a = new ArrayList<>(List.of("report", "--aiconfig", cfg.toString()));
        AiConfig c = (AiConfig) call("loadAiConfig", LIST_STR, a);
        Asserts.assertEquals("provider 来自文件", "qwen", c.getProvider());
        Asserts.assertEquals("baseUrl 来自文件", "https://local.test/v1", c.getBaseUrl());
        Asserts.assertEquals("model 来自文件", "qwen-max", c.getModel());
        Asserts.assertEquals("stageBTopK 来自文件", 7, c.getStageBTopK());
        Asserts.assertEquals("costGate 来自文件", 1234.0, c.getCostGateWarnTokens());
        Asserts.assertEquals("httpProxy 来自文件", "http://p:1", c.getHttpProxy());
        Asserts.assertEquals("httpsProxy 来自文件", "http://s:2", c.getHttpsProxy());
        Asserts.assertTrue("blockPrivateEndpoints 来自文件", c.isBlockPrivateEndpoints());
        Asserts.assertEquals("文件无 aiApiKey 时 Key 为空", "", c.getApiKey());
        // 语义：持久化配置文件存在即视为已启用（Main 在读取成功后无条件 setEnabled(true)）
        Asserts.assertTrue("配置文件存在应置 enabled", c.isEnabled());
    }

    public void testLoadAiConfigBadNumberDegrades() throws Exception {
        Path dir = tempDir("bdfmain-aicfg-bad");
        Path cfg = dir.resolve("bad.properties");
        // 解析 stageBTopK 抛 NumberFormatException → catch 分支：此前已生效的字段保留，其后字段用默认
        Files.writeString(cfg, "aiProvider=azure\naiModel=m1\nstageBTopK=not-a-number\n", StandardCharsets.UTF_8);
        AiConfig c = (AiConfig) call("loadAiConfig", LIST_STR,
                new ArrayList<>(List.of("--aiconfig", cfg.toString())));
        Asserts.assertEquals("已读取的 provider 保留", "azure", c.getProvider());
        Asserts.assertEquals("已读取的 model 保留", "m1", c.getModel());
        Asserts.assertEquals("异常后 stageBTopK 维持默认", 15, c.getStageBTopK());
        Asserts.assertEquals("异常后 costGate 维持默认", 8000.0, c.getCostGateWarnTokens());
    }

    public void testLoadAiConfigCliOverrides() throws Exception {
        Path dir = tempDir("bdfmain-aicfg-override");
        Path cfg = dir.resolve("cfg.properties");
        Files.writeString(cfg, "aiProvider=qwen\naiModel=from-file\n", StandardCharsets.UTF_8);
        List<String> a = new ArrayList<>(List.of("--aiconfig", cfg.toString(),
                "--apikey", "sk-cli-key", "--provider", "ollama",
                "--baseurl", "http://127.0.0.1:1/v1", "--model", "cli-model"));
        AiConfig c = (AiConfig) call("loadAiConfig", LIST_STR, a);
        Asserts.assertEquals("CLI apiKey 覆盖", "sk-cli-key", c.getApiKey());
        Asserts.assertEquals("CLI provider 覆盖文件", "ollama", c.getProvider());
        Asserts.assertEquals("CLI baseurl 覆盖文件", "http://127.0.0.1:1/v1", c.getBaseUrl());
        Asserts.assertEquals("CLI model 覆盖文件", "cli-model", c.getModel());
        Asserts.assertTrue("有 Key 应置 enabled", c.isEnabled());
    }

    public void testLoadAiConfigFileWithoutKeyStillEnabled() throws Exception {
        Path dir = tempDir("bdfmain-aicfg-emptykey");
        Path cfg = dir.resolve("cfg.properties");
        Files.writeString(cfg, "aiApiKey=\n", StandardCharsets.UTF_8);
        AiConfig c = (AiConfig) call("loadAiConfig", LIST_STR,
                new ArrayList<>(List.of("--aiconfig", cfg.toString())));
        Asserts.assertEquals("空 Key", "", c.getApiKey());
        // 文件存在会把 enabled 置 true（与 UI 语义一致），随后空 Key 不再改变该值
        Asserts.assertTrue("配置文件存在即视为已启用", c.isEnabled());
    }

    // ---------- loadProjectContext ----------

    public void testLoadProjectContextNoFlag() throws Exception {
        Asserts.assertNull("无 --project 返回 null",
                call("loadProjectContext", LIST_STR, new ArrayList<>(List.of("ai", "o", "n"))));
    }

    public void testLoadProjectContextValidDir() throws Exception {
        Path proj = tempDir("bdfmain-proj");
        try {
            Files.writeString(proj.resolve("pom.xml"),
                    "<project><modelVersion>4.0.0</modelVersion>"
                            + "<groupId>com.test</groupId><artifactId>demo</artifactId>"
                            + "<version>1.0.0</version></project>\n", StandardCharsets.UTF_8);
            ProjectContext ctx = (ProjectContext) call("loadProjectContext", LIST_STR,
                    new ArrayList<>(List.of("--project", proj.toString())));
            Asserts.assertNotNull("有效目录应返回上下文", ctx);
            Asserts.assertFalse("有效目录不应为空上下文", ctx.isEmpty());
            Asserts.assertEquals("CLI 应走多项目索引渲染: " + ctx.getBuildSystem(), true,
                    ctx.getBuildSystem().startsWith("多项目仓库（1 个项目）"));
            Asserts.assertNotNull("模块列表不应为 null（聚合视图占位）", ctx.getModules());
            Asserts.assertTrue("上下文摘要应包含项目索引细节: " + ctx.getSummary(),
                    ctx.getSummary().contains("Maven") && ctx.getSummary().contains("."));
        } finally {
            com.bempdiff.ai.context.ProjectContextCache.clear(proj.toString());
        }
    }

    public void testLoadProjectContextCatchBranch() throws Exception {
        // --project 位于末尾，取 index+1 触发 IndexOutOfBoundsException（RuntimeException）→ catch 分支返回 null
        ProjectContext ctx = (ProjectContext) call("loadProjectContext", LIST_STR,
                new ArrayList<>(List.of("ai", "o", "n", "--project")));
        Asserts.assertNull("异常应被降级为 null", ctx);
    }

    public void testLoadProjectContextInvalidDir() throws Exception {
        Path nope = tempDir("bdfmain-proj-none").resolve("not-created");
        ProjectContext ctx = (ProjectContext) call("loadProjectContext", LIST_STR,
                new ArrayList<>(List.of("--project", nope.toString())));
        Asserts.assertNull("无效目录应降级为 null", ctx);
    }

    // ---------- requireArgs（仅合法分支） ----------

    public void testRequireArgsLegalBranch() throws Exception {
        String[] args = {"compare", "old.zip", "new.zip", "--expand-all"};
        call("requireArgs", new Class[]{String[].class, int.class, String.class}, args, 3, "compare");
        call("requireArgs", new Class[]{String[].class, int.class, String.class}, args, 4, "compare");
        // 走到这里即未触发 System.exit（不足分支会终止 JVM，故不测）
    }

    // ---------- prepCompare + decompileTop + printTree + buildTextMap ----------

    public void testPrepCompareAndDownstreamHelpers() throws Exception {
        Path[] pair = warPair("prep");
        Object prep = call("prepCompare", PATH_BOOL, pair[0], pair[1], false);
        Class<?> prepCls = prep.getClass();
        Asserts.assertEquals("PrepResult 应为 Main 私有嵌套记录", "com.bempdiff.Main$PrepResult",
                prepCls.getName());
        PackageSnapshot os = (PackageSnapshot) accessor(prepCls, prep, "oldSnap");
        PackageSnapshot ns = (PackageSnapshot) accessor(prepCls, prep, "newSnap");
        DiffResult r = (DiffResult) accessor(prepCls, prep, "r");
        Asserts.assertEquals("老包版本", "1.0.0", os.getVersion());
        Asserts.assertEquals("新包版本", "1.0.1", ns.getVersion());
        Asserts.assertTrue("老包应有 A.class", os.getEntries().containsKey("WEB-INF/classes/com/internal/A.class"));
        Asserts.assertTrue("差异结果应含 MODIFIED", !r.get(com.bempdiff.diff.DiffStatus.MODIFIED).isEmpty());

        // printTree：真实差异 + 快照，覆盖各状态分组与缩进
        call("printTree", new Class[]{DiffResult.class, PackageSnapshot.class, PackageSnapshot.class}, r, os, ns);

        // buildTextMap：CONFIG/JS 候选（modified 取新侧分类、deleted 取老侧分类）
        Class<?>[] bt = {DiffResult.class, PackageSnapshot.class, PackageSnapshot.class, int.class};
        @SuppressWarnings("unchecked")
        Map<String, DecompiledUnit> full = (Map<String, DecompiledUnit>) call("buildTextMap", bt, r, os, ns, 10);
        Asserts.assertTrue("文本候选应含 legacy.xml: " + full.keySet(),
                full.containsKey("WEB-INF/classes/conf/legacy.xml"));
        Asserts.assertTrue("文本候选应含 gone.xml: " + full.keySet(),
                full.containsKey("WEB-INF/classes/conf/gone.xml"));
        Asserts.assertTrue("文本候选应含 app.js: " + full.keySet(), full.containsKey("WEB-INF/static/app.js"));
        Asserts.assertEquals("legacy.xml 处理引擎应为文本类: " + full.get("WEB-INF/classes/conf/legacy.xml").getEngine(),
                true, full.get("WEB-INF/classes/conf/legacy.xml").isOk());
        @SuppressWarnings("unchecked")
        Map<String, DecompiledUnit> limited = (Map<String, DecompiledUnit>) call("buildTextMap", bt, r, os, ns, 1);
        Asserts.assertEquals("topK=1 应截断为 1 项", 1, limited.size());
        @SuppressWarnings("unchecked")
        Map<String, DecompiledUnit> zero = (Map<String, DecompiledUnit>) call("buildTextMap", bt, r, os, ns, 0);
        Asserts.assertEquals("topK=0 应为空", 0, zero.size());

        // decompileTop：真实 Decompiler（CFR 在 classpath 上 → 进程内反编译）
        Decompiler dec = new Decompiler(null, "java");
        @SuppressWarnings("unchecked")
        Map<String, DecompiledUnit> top1 = (Map<String, DecompiledUnit>) call("decompileTop",
                new Class[]{prepCls, Decompiler.class, int.class}, prep, dec, 1);
        Asserts.assertEquals("topK=1 至多 1 个候选", 1, top1.size());
        for (Map.Entry<String, DecompiledUnit> e : top1.entrySet()) {
            DecompiledUnit u = e.getValue();
            Asserts.assertTrue("反编译应成功: " + e.getKey() + " -> " + u.getError(), u.isOk());
            Asserts.assertNotNull("应有源码文本", u.getNewSource() != null ? u.getNewSource() : u.getOldSource());
        }
        @SuppressWarnings("unchecked")
        Map<String, DecompiledUnit> top0 = (Map<String, DecompiledUnit>) call("decompileTop",
                new Class[]{prepCls, Decompiler.class, int.class}, prep, dec, 0);
        Asserts.assertEquals("topK=0 应为空 map", 0, top0.size());
    }

    private static Object accessor(Class<?> recordCls, Object target, String name) throws Exception {
        Method m = recordCls.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    // ---------- printFolderTree ----------

    public void testPrintFolderTreeWithRealResult() throws Exception {
        Path left = tempDir("bdfmain-pft-left");
        Path right = tempDir("bdfmain-pft-right");
        Files.writeString(left.resolve("same.txt"), "same");
        Files.writeString(right.resolve("same.txt"), "same");
        Files.createDirectories(left.resolve("src/sub"));
        Files.createDirectories(right.resolve("src/sub"));
        Files.writeString(left.resolve("src/app.js"), "var x = 1;\nvar y = 2;\n");
        Files.writeString(right.resolve("src/app.js"), "var x = 1;\nvar y = 3;\n");
        Files.writeString(left.resolve("src/sub/only-left.md"), "# left\n");
        Files.writeString(right.resolve("src/sub/only-right.md"), "# right\n");
        Files.writeString(right.resolve("added.txt"), "brand new file\nwith two lines\n");

        FolderDiff.FolderDiffResult res = FolderDiff.compare(left, right, FolderDiff.Options.defaults());
        // 真实结果里同时含 DIR/MODIFIED(带行级 diff)/LEFT_ONLY/RIGHT_ONLY/ADDED 节点
        call("printFolderTree", new Class[]{List.class, String.class}, res.roots, "");
        call("printFolderTree", new Class[]{List.class, String.class}, new ArrayList<FolderDiff.FolderEntry>(), "  ");
        Asserts.assertTrue("差异树根节点应非空", !res.roots.isEmpty());
        Asserts.assertTrue("应至少识别出一处内容差异: " + res.summary.getContentChanged(),
                res.summary.getContentChanged() >= 1);
    }

    /** 覆盖 Main 内联的 compare 智能排序前置工具（PackageVersion 识别格式）与空 map 分支组合。 */
    public void testLayerCountOnParsedSnapshot() throws Exception {
        Path[] pair = warPair("layers");
        Object prep = call("prepCompare", PATH_BOOL, pair[0], pair[1], true);
        PackageSnapshot os = (PackageSnapshot) accessor(prep.getClass(), prep, "oldSnap");
        Object l0 = call("layerCount", MAP_LAYER, os.getEntries(), Layer.L0);
        Object l1 = call("layerCount", MAP_LAYER, os.getEntries(), Layer.L1);
        Object l2 = call("layerCount", MAP_LAYER, os.getEntries(), Layer.L2);
        int sum = ((Integer) l0) + ((Integer) l1) + ((Integer) l2);
        Asserts.assertEquals("三层计数之和应等于条目总数", os.getEntries().size(), sum);
        Asserts.assertTrue("L1 应至少含 A.class", ((Integer) l1) >= 1);
    }
}

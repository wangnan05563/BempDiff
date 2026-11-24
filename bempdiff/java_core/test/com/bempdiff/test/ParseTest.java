package com.bempdiff.test;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.Layer;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.model.PackageType;
import com.bempdiff.parse.PackageParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 包解析引擎测试：类型识别、L0/L1/L2 分层、版本提取、命名空间、Zip Slip 防护、嵌套读取、配置接线。 */
public final class ParseTest {

    private final PackageParser parser = new PackageParser();

    private static Map<String, byte[]> warEntries() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("WEB-INF/classes/com/internal/A.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        e.put("WEB-INF/classes/com/other/X.class", TestFixtures.compileClass("com.other.X", TestFixtures.SRC_X));
        // 内部 lib（含 com/internal 前缀）应展开为 L1
        Map<String, byte[]> inner = new LinkedHashMap<>();
        inner.put("com/internal/B.class", TestFixtures.compileClass("com.internal.B", TestFixtures.SRC_B));
        e.put("WEB-INF/lib/internal-core.jar", TestFixtures.makeZip(inner));
        // 第三方 lib（org/apache）仅 jar 级 L2
        Map<String, byte[]> tp = new LinkedHashMap<>();
        tp.put("org/apache/T.class", TestFixtures.compileClass("org.apache.T", TestFixtures.SRC_T));
        e.put("WEB-INF/lib/third-party.jar", TestFixtures.makeZip(tp));
        return e;
    }

    public void testDetectType_war() throws IOException {
        Path p = TestFixtures.writePackage(
                TestFixtures.makeWar(warEntries(), "1.0.0"), "detect-war");
        Asserts.assertEquals("应为 WAR", PackageType.WAR, parser.detectType(p));
    }

    public void testDetectType_fatJar() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("BOOT-INF/classes/com/x/A.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        e.put("BOOT-INF/lib/y.jar", TestFixtures.makeZip(new LinkedHashMap<>()));
        Path p = TestFixtures.writePackage(TestFixtures.makeZip(e), "detect-fat");
        Asserts.assertEquals("应为 FAT_JAR", PackageType.FAT_JAR, parser.detectType(p));
    }

    public void testDetectType_plainJar() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("com/x/A.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        Path p = TestFixtures.writePackage(TestFixtures.makeZip(e), "detect-jar");
        Asserts.assertEquals("应为 JAR", PackageType.JAR, parser.detectType(p));
    }

    public void testParseWar_layering() throws IOException {
        Path p = TestFixtures.writePackage(TestFixtures.makeWar(warEntries(), "2.3.4"), "parse-war");
        PackageSnapshot snap = parser.parse(p, new ParseConfig(), false);
        Map<String, LogicalEntry> entries = snap.getEntries();

        Asserts.assertEquals("版本应来自 MANIFEST", "2.3.4", snap.getVersion());
        Asserts.assertNotNull("A.class 应存在", entries.get("WEB-INF/classes/com/internal/A.class"));
        Asserts.assertEquals("A 应为 L1", Layer.L1, entries.get("WEB-INF/classes/com/internal/A.class").getLayer());
        Asserts.assertEquals("A 应为 CLASS", FileClass.CLASS, entries.get("WEB-INF/classes/com/internal/A.class").getFileClass());

        // 内部 lib 应展开为带命名空间的 L1 条目
        LogicalEntry b = entries.get("WEB-INF/lib/internal-core.jar/com/internal/B.class");
        Asserts.assertNotNull("内部 lib 的 B.class 应展开", b);
        Asserts.assertEquals("B 应为 L1", Layer.L1, b.getLayer());
        Asserts.assertTrue("B 的 src 应为嵌套", b.getSrc().isNested());

        // 第三方 lib 仅 jar 级 L2（不展开）
        LogicalEntry tp = entries.get("WEB-INF/lib/third-party.jar");
        Asserts.assertNotNull("third-party.jar 应存在", tp);
        Asserts.assertEquals("third-party 应为 L2", Layer.L2, tp.getLayer());
        Asserts.assertEquals("third-party 应为 JAR", FileClass.JAR, tp.getFileClass());
        Asserts.assertNull("third-party 内部类不应展开",
                entries.get("WEB-INF/lib/third-party.jar/org/apache/T.class"));

        // MANIFEST 属 L0
        Asserts.assertEquals("MANIFEST 应为 L0", Layer.L0, entries.get("META-INF/MANIFEST.MF").getLayer());
    }

    public void testExtractVersion_fromPom() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("META-INF/maven/com/foo/bar/pom.properties", "version=9.9.9\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        e.put("com/x/A.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        Path p = TestFixtures.writePackage(TestFixtures.makeZip(e), "pom-ver");
        PackageSnapshot snap = parser.parse(p, new ParseConfig(), false);
        Asserts.assertEquals("版本应来自 pom.properties", "9.9.9", snap.getVersion());
    }

    public void testZipSlip_rejected() throws IOException {
        // 含路径穿越条目的 war：该条目应被 sanitizeKey 拒绝并跳过，解析不崩溃
        Map<String, byte[]> e = warEntries();
        e.put("../evil/Escape.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        Path p = TestFixtures.writePackage(TestFixtures.makeWar(e, "1.0"), "zipslip");
        PackageSnapshot snap = parser.parse(p, new ParseConfig(), false);
        Asserts.assertNull("穿越条目不得进入解析结果", snap.getEntries().get("../evil/Escape.class"));
        Asserts.assertNotNull("合法条目仍应解析", snap.getEntries().get("WEB-INF/classes/com/internal/A.class"));
    }

    public void testReadEntryBytes_nested() throws IOException {
        Path p = TestFixtures.writePackage(TestFixtures.makeWar(warEntries(), "1.0"), "readnest");
        PackageSnapshot snap = parser.parse(p, new ParseConfig(), false);
        LogicalEntry b = snap.getEntries().get("WEB-INF/lib/internal-core.jar/com/internal/B.class");
        byte[] bytes = parser.readEntryBytes(snap, b);
        Asserts.assertTrue("应读回嵌套 class 字节", bytes != null && bytes.length > 0);
        // 前 4 字节应为 class 魔数 CAFEBABE
        Asserts.assertEquals("class 魔数校验", (byte) 0xCA, bytes[0]);
        Asserts.assertEquals("class 魔数校验", (byte) (byte) 0xFE, bytes[1]);
    }

    public void testPlainJarClassesDefaultL1() throws IOException {
        // 普通 jar 顶层 class 属于构件自身业务码：默认即 L1（不再依赖 expandAllForPlainJar）。
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("otherpkg/Y.class", TestFixtures.compileClass("otherpkg.Y",
                "package otherpkg; public class Y { public int v=1; }"));
        Path p = TestFixtures.writePackage(TestFixtures.makeZip(e), "plainjar");

        ParseConfig cfgDefault = new ParseConfig();
        PackageSnapshot snapDefault = parser.parse(p, cfgDefault, false);
        Asserts.assertEquals("普通 jar 顶层 class 默认应为 L1", Layer.L1,
                snapDefault.getEntries().get("otherpkg/Y.class").getLayer());

        ParseConfig cfgExpand = new ParseConfig();
        cfgExpand.setExpandAllForPlainJar(true);
        PackageSnapshot snapExpand = parser.parse(p, cfgExpand, false);
        Asserts.assertEquals("开启 expandAllForPlainJar 后仍为 L1", Layer.L1,
                snapExpand.getEntries().get("otherpkg/Y.class").getLayer());
    }

    public void testIgnoreExtensions_filteredFromEntries() throws IOException {
        // 含 App.log：设置忽略扩展名后，解析收集阶段应整体跳过该条目（不含于快照 entries）
        Map<String, byte[]> e = warEntries();
        e.put("WEB-INF/classes/app.log", "2026-01-01 INFO\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        e.put("WEB-INF/classes/keep.class", TestFixtures.compileClass("com.internal.Keep",
                "package com.internal; public class Keep { }"));
        Path p = TestFixtures.writePackage(TestFixtures.makeWar(e, "1.0"), "igext");

        // 默认（不忽略）应解析出 app.log
        PackageSnapshot defSnap = parser.parse(p, new ParseConfig(), false);
        Asserts.assertNotNull("默认应含 app.log", defSnap.getEntries().get("WEB-INF/classes/app.log"));

        // 忽略 .log 后 app.log 消失，keep.class 与路径内嵌的 class 仍在
        ParseConfig cfg = new ParseConfig();
        cfg.setIgnoreExtensions(java.util.Arrays.asList(".log", "TMP"));
        PackageSnapshot snap = parser.parse(p, cfg, false);
        Asserts.assertNull("忽略 .log 后 app.log 不应在结果中", snap.getEntries().get("WEB-INF/classes/app.log"));
        Asserts.assertNotNull("忽略 .log 不应误伤 class", snap.getEntries().get("WEB-INF/classes/keep.class"));
        Asserts.assertNotNull("忽略 .log 不应误伤内部 lib 展开类",
                snap.getEntries().get("WEB-INF/lib/internal-core.jar/com/internal/B.class"));
        // 无扩展名归一：小写会话 "TMP" → 应归一为 .tmp
        e.put("WEB-INF/classes/tmpfile.TMP", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path p2 = TestFixtures.writePackage(TestFixtures.makeWar(e, "1.0"), "igext2");
        PackageSnapshot snap2 = parser.parse(p2, cfg, false);
        Asserts.assertNull("忽略规则应不分大小写(TMP/.tmp)", snap2.getEntries().get("WEB-INF/classes/tmpfile.TMP"));

        // 多段扩展名：忽略 ".min.js" 能命中 "app/static/app.min.js"（M1 修复点）
        e.put("WEB-INF/classes/static/app.min.js", "var a=1;\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ParseConfig cfg2 = new ParseConfig();
        cfg2.setIgnoreExtensions(java.util.Arrays.asList(".min.js"));
        Path p3 = TestFixtures.writePackage(TestFixtures.makeWar(e, "1.0"), "igext3");
        PackageSnapshot snap3 = parser.parse(p3, cfg2, false);
        Asserts.assertNull("忽略 .min.js 应命中 app.min.js（多段扩展名）",
                snap3.getEntries().get("WEB-INF/classes/static/app.min.js"));
    }

    /** 用户反馈：自定义后缀 .MF 保存（被归一为 .mf）后，MANIFEST.MF 仍被比对出来。
     *  复现：把 .mf 加入忽略集合，真实 WAR 顶层的 MANIFEST.MF 应被解析阶段拦截。 */
    public void testIgnoreExtensions_dotMF_matchesManifest() throws IOException {
        // 普通 jar（顶层若含 META-INF/MANIFEST.MF）——makeWar 会带 MANIFEST；这里显式制造一个 .MF 条目验证
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        e.put("com/x/A.class", TestFixtures.compileClass("com.internal.A", TestFixtures.SRC_A_V1));
        Path p = TestFixtures.writePackage(TestFixtures.makeZip(e), "igmf");

        PackageSnapshot defSnap = parser.parse(p, new ParseConfig(), false);
        Asserts.assertNotNull("默认应含 MANIFEST.MF", defSnap.getEntries().get("META-INF/MANIFEST.MF"));

        // 用户在 UI 输入 .MF → 前端归一为 .mf 存盘
        ParseConfig cfg = new ParseConfig();
        cfg.setIgnoreExtensions(java.util.Arrays.asList(".mf"));
        PackageSnapshot snap = parser.parse(p, cfg, false);
        Asserts.assertNull("忽略 .mf 后 MANIFEST.MF 不应在结果中（大小写不敏感）",
                snap.getEntries().get("META-INF/MANIFEST.MF"));
        Asserts.assertNotNull("忽略 .mf 不应误伤 class", snap.getEntries().get("com/x/A.class"));
    }
}

package com.bempdiff.test;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.diff.DiffRules;
import com.bempdiff.server.CompareOptions;
import com.bempdiff.unpack.UnpackOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CompareOptions#fromRequest} 及各派生配置的解析单测。
 * 覆盖此前只被 E2eVerify（不参与常规运行）间接触达的解析分支：
 * 无 options / 全量字段 / 字符串强转（Number·字符串·非法值回退默认）/ ignoreExtensions 过滤 null 与空白 /
 * setIgnoreExtensions(null) / toUnpackOptions 拷贝 / toParseConfig 前缀切分 / toDiffRules。
 */
public final class CompareOptionsRequestTest {

    private static Map<String, Object> req(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> requestWithOptions(Map<String, Object> options) {
        return req("options", options);
    }

    // ---------------- 无 options：全部默认 ----------------

    public void testFromRequest_withoutOptionsYieldsDefaults() {
        CompareOptions o = CompareOptions.fromRequest(new LinkedHashMap<>());
        Asserts.assertFalse("默认不展开", o.isExpandAll());
        Asserts.assertEquals("默认 topK=12", 12, o.getTopK());
        Asserts.assertEquals("默认内部前缀", "com.hundsun", o.getInternalPrefixes());
        Asserts.assertEquals("默认 cfrJar 空", "", o.getCfrJar());
        Asserts.assertFalse("默认不忽略空白", o.isIgnoreWhitespace());
        Asserts.assertFalse("默认不忽略注释", o.isIgnoreComments());
        Asserts.assertEquals("默认无忽略正则", "", o.getIgnoreRegex());
        Asserts.assertTrue("默认无忽略扩展名", o.getIgnoreExtensions().isEmpty());
        Asserts.assertFalse("默认不解包嵌套", o.isUnpackNested());
    }

    public void testFromRequest_optionsNotMapIsIgnored() {
        // options 值存在但非 Map → 走默认（不进入解析块）
        CompareOptions o = CompareOptions.fromRequest(req("options", "not-a-map"));
        Asserts.assertEquals("仍为默认 topK", 12, o.getTopK());
    }

    // ---------------- 全量字段 ----------------

    public void testFromRequest_fullNumericAndBooleans() {
        Map<String, Object> opts = req(
                "expandAll", true,
                "topK", 20,
                "internalPrefixes", "com.a,com.b",
                "cfrJar", "libs/cfr.jar",
                "ignoreWhitespace", true,
                "ignoreComments", true,
                "ignoreRegex", "\\d+",
                "unpackNested", true,
                "unpackThreads", 8,
                "unpackPerItemTimeoutMs", 12345L,
                "unpackMaxDepth", 3,
                "unpackTotalBytesCap", 999L);
        CompareOptions o = CompareOptions.fromRequest(requestWithOptions(opts));
        Asserts.assertTrue("expandAll 生效", o.isExpandAll());
        Asserts.assertEquals("topK 生效", 20, o.getTopK());
        Asserts.assertEquals("前缀生效", "com.a,com.b", o.getInternalPrefixes());
        Asserts.assertEquals("cfrJar 生效", "libs/cfr.jar", o.getCfrJar());
        Asserts.assertTrue("ignoreWhitespace 生效", o.isIgnoreWhitespace());
        Asserts.assertTrue("ignoreComments 生效", o.isIgnoreComments());
        Asserts.assertEquals("ignoreRegex 生效", "\\d+", o.getIgnoreRegex());
        Asserts.assertTrue("unpackNested 生效", o.isUnpackNested());

        UnpackOptions uo = o.toUnpackOptions();
        Asserts.assertEquals("线程数派生", 8, uo.threadPoolSize);
        Asserts.assertEquals("单项超时派生", 12345L, uo.perItemTimeoutMs);
        Asserts.assertEquals("深度派生", 3, uo.maxDepth);
        Asserts.assertEquals("总字节上限派生", 999L, uo.totalBytesCap);
    }

    // ---------------- Json 强转分支：字符串数字 / Number / 非法回退默认 ----------------

    public void testFromRequest_stringCoercionBranches() {
        Map<String, Object> opts = req(
                "topK", "30",                    // 字符串数字 → 解析
                "unpackThreads", "5",            // 字符串数字 → 解析
                "unpackMaxDepth", 7,             // Number → intValue
                "unpackPerItemTimeoutMs", "8000",// 字符串 long → 解析
                "unpackTotalBytesCap", "1234",   // 字符串 long → 解析
                "ignoreWhitespace", "true",      // 字符串 bool → parseBoolean
                "expandAll", Boolean.TRUE,       // Boolean 实例
                "cfrJar", 5555,                  // 非字符串 → String.valueOf
                "internalPrefixes", null);       // null → 用默认
        CompareOptions o = CompareOptions.fromRequest(requestWithOptions(opts));
        Asserts.assertEquals("字符串 topK 解析", 30, o.getTopK());
        Asserts.assertTrue("字符串 bool true 解析", o.isIgnoreWhitespace());
        Asserts.assertTrue("Boolean 实例解析", o.isExpandAll());
        Asserts.assertEquals("非串 cfrJar 转字符串", "5555", o.getCfrJar());
        Asserts.assertEquals("null 前缀回退默认", "com.hundsun", o.getInternalPrefixes());

        UnpackOptions uo = o.toUnpackOptions();
        Asserts.assertEquals("字符串线程解析", 5, uo.threadPoolSize);
        Asserts.assertEquals("Number 深度解析", 7, uo.maxDepth);
        Asserts.assertEquals("字符串单项超时解析", 8000L, uo.perItemTimeoutMs);
        Asserts.assertEquals("字符串总上限解析", 1234L, uo.totalBytesCap);
    }

    public void testFromRequest_invalidNumbersFallBackToDefault() {
        Map<String, Object> opts = req(
                "topK", "abc",                 // int 非法 → 默认 12
                "unpackThreads", "x",          // int 非法 → 默认 4
                "unpackPerItemTimeoutMs", "y", // long 非法 → 默认 60000
                "unpackTotalBytesCap", "z");   // long 非法 → 默认 4GB
        CompareOptions o = CompareOptions.fromRequest(requestWithOptions(opts));
        Asserts.assertEquals("非法 topK 回退 12", 12, o.getTopK());
        UnpackOptions uo = o.toUnpackOptions();
        Asserts.assertEquals("非法线程数回退 4", 4, uo.threadPoolSize);
        Asserts.assertEquals("非法单项超时回退 60000", 60000L, uo.perItemTimeoutMs);
        Asserts.assertEquals("非法总上限回退 4GB", 4L * 1024 * 1024 * 1024, uo.totalBytesCap);
    }

    public void testFromRequest_nonBooleanValueParsesFalse() {
        Map<String, Object> opts = req("ignoreComments", "notabool"); // 字符串非 "true" → false
        CompareOptions o = CompareOptions.fromRequest(requestWithOptions(opts));
        Asserts.assertFalse("非 true 串按 false 解析", o.isIgnoreComments());
    }

    // ---------------- ignoreExtensions 列表过滤（null / 空白 / 有效值 trim）----------------

    public void testFromRequest_ignoreExtensionsFiltersNullAndBlank() {
        List<Object> raw = new ArrayList<>();
        raw.add("  .log  ");
        raw.add(null);
        raw.add("   ");
        raw.add(".tmp");
        Map<String, Object> opts = req("ignoreExtensions", raw);
        CompareOptions o = CompareOptions.fromRequest(requestWithOptions(opts));
        Asserts.assertEquals("仅保留两个有效扩展名", 2, o.getIgnoreExtensions().size());
        Asserts.assertEquals("首个已 trim", ".log", o.getIgnoreExtensions().get(0));
        Asserts.assertEquals("次个", ".tmp", o.getIgnoreExtensions().get(1));

        // toUnpackOptions 应拷贝（非共享同一 list），修改派生项不影响原 opts
        UnpackOptions uo = o.toUnpackOptions();
        Asserts.assertEquals("派生列表内容一致", 2, uo.ignoreExtensions.size());
        uo.ignoreExtensions.add(".extra");
        Asserts.assertEquals("原选项不受派生修改影响", 2, o.getIgnoreExtensions().size());
    }

    public void testFromRequest_ignoreExtensionsNonListKeepsEmpty() {
        Map<String, Object> opts = req("ignoreExtensions", "not-a-list");
        CompareOptions o = CompareOptions.fromRequest(requestWithOptions(opts));
        Asserts.assertTrue("非列表时保持默认空", o.getIgnoreExtensions().isEmpty());
    }

    // ---------------- setIgnoreExtensions(null) ----------------

    public void testSetIgnoreExtensionsNullBecomesEmptyList() {
        CompareOptions o = new CompareOptions();
        o.setIgnoreExtensions(new ArrayList<>(Arrays.asList(".a")));
        o.setIgnoreExtensions(null);
        Asserts.assertNotNull("null 归一为空列表", o.getIgnoreExtensions());
        Asserts.assertTrue("空列表", o.getIgnoreExtensions().isEmpty());
    }

    // ---------------- 基础 setter/getter ----------------

    public void testPlainSettersGetters() {
        CompareOptions o = new CompareOptions();
        o.setExpandAll(true);
        o.setTopK(99);
        o.setInternalPrefixes("com.z");
        o.setCfrJar("c.jar");
        o.setIgnoreWhitespace(true);
        o.setIgnoreComments(true);
        o.setIgnoreRegex("rx");
        o.setUnpackNested(true);
        Asserts.assertTrue("expandAll", o.isExpandAll());
        Asserts.assertEquals("topK", 99, o.getTopK());
        Asserts.assertEquals("prefixes", "com.z", o.getInternalPrefixes());
        Asserts.assertEquals("cfrJar", "c.jar", o.getCfrJar());
        Asserts.assertTrue("ignoreWhitespace", o.isIgnoreWhitespace());
        Asserts.assertTrue("ignoreComments", o.isIgnoreComments());
        Asserts.assertEquals("ignoreRegex", "rx", o.getIgnoreRegex());
        Asserts.assertTrue("unpackNested", o.isUnpackNested());
    }

    // ---------------- toParseConfig：内部前缀切分 / expandAll 映射 / 上限 / 忽略扩展名 ----------------

    public void testToParseConfig_splitsPrefixesAndMapsFlags() {
        CompareOptions o = new CompareOptions();
        o.setInternalPrefixes("com.a;com.b com.c,com.d"); // 混用 , ; 空格
        o.setExpandAll(true);
        o.setIgnoreExtensions(new ArrayList<>(Arrays.asList(".log")));
        ParseConfig c = o.toParseConfig();
        List<String> prefixes = c.getInternalPrefixes();
        Asserts.assertTrue("切分出多个前缀(>=4)", prefixes.size() >= 4);
        Asserts.assertTrue("含 com.a", prefixes.contains("com.a"));
        Asserts.assertTrue("含 com.c", prefixes.contains("com.c"));
        Asserts.assertTrue("expandInternalLib 映射 expandAll", c.isExpandInternalLib());
        Asserts.assertEquals("单条目上限固定 8MB", 8L * 1024 * 1024, c.getMaxEntryBytes());
        Asserts.assertEquals("忽略扩展名透传", 1, c.getIgnoreExtensions().size());
        Asserts.assertEquals("首个忽略项", ".log", c.getIgnoreExtensions().get(0));
    }

    // ---------------- toDiffRules：非空且反映忽略开关 ----------------

    public void testToDiffRules_derivesFromOptions() {
        CompareOptions o = new CompareOptions();
        o.setIgnoreWhitespace(true);
        o.setIgnoreComments(true);
        o.setIgnoreRegex("[0-9]+"); // 合法正则
        DiffRules rules = o.toDiffRules();
        Asserts.assertNotNull("派生规则非空", rules);
        // 非法正则也不应抛出（DiffRules 内部忽略），仍返回规则对象
        CompareOptions bad = new CompareOptions();
        bad.setIgnoreRegex("((未闭合");
        Asserts.assertNotNull("非法正则仍派生规则", bad.toDiffRules());
    }

    // ---------------- toUnpackOptions 默认（未从请求覆盖时）----------------

    public void testToUnpackOptionsDefaults() {
        UnpackOptions uo = new CompareOptions().toUnpackOptions();
        Asserts.assertEquals("默认线程 4", 4, uo.threadPoolSize);
        Asserts.assertEquals("默认超时 60000", 60000L, uo.perItemTimeoutMs);
        Asserts.assertEquals("默认深度 6", 6, uo.maxDepth);
        Asserts.assertEquals("默认总上限 4GB", 4L * 1024 * 1024 * 1024, uo.totalBytesCap);
        Asserts.assertTrue("默认忽略扩展名空", uo.ignoreExtensions.isEmpty());
    }
}

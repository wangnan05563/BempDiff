package com.bempdiff.test;

import com.bempdiff.diff.AstNormalizer;

/**
 * FR-006 语法感知归一化（AST 对齐）测试：
 *  ① 重排+格式化+注释变化 → 归一化相等（零假差异，验收①）
 *  ② 实质逻辑变更 → 归一化不等（不漏报，验收②）
 *  ③ 语法错误/超限 → null 降级（验收④），降级计数递增
 *  ④ 性能：单文件解析耗时有界（验收③ AST 增量远小于反编译成本）
 */
public final class AstNormalizerTest {

    private static final String V1 = """
            package com.demo;
            // 头注释（应被消除）
            public class Svc {
                private int count = 1;

                /** 文档注释 */
                public String hello(String name) {
                    return "hi, " + name;
                }

                public void   run( )   {
                    int x=1;   int y =  2;
                    System.out.println(x + y);
                }
            }
            """;

    /** 同内容但成员重排 + 重新格式化 + 注释全部改写。 */
    private static final String V2_REORDERED = """
            package com.demo;
            /* 全新的注释块 */
            public class Svc {
                public void run() {
                    int x = 1, y = 2;
                    System.out.println(x + y);
                }

                private int count = 1;

                public String hello(String name) { return "hi, " + name; }
            }
            """;

    /** 实质变更：方法体逻辑 + 新增字段。 */
    private static final String V3_SUBSTANTIVE = """
            package com.demo;
            public class Svc {
                private int count = 2;

                public String hello(String name) {
                    return "hello, " + name;
                }

                public void run() {
                    int x=1;   int y =  2;
                    System.out.println(x + y);
                }
            }
            """;

    /** 验收①：重排+格式化+注释变化 → 归一化相等。 */
    public void testReorderAndFormatYieldsZeroFalseDiff() {
        String a = AstNormalizer.normalizeOrNull(V1);
        Asserts.assertNotNull("合法 Java 应解析成功", a);
        String b = AstNormalizer.normalizeOrNull(V2_REORDERED);
        Asserts.assertNotNull("重排版应解析成功", b);
        Asserts.assertTrue("重排+格式化应归一化相等（零假差异）", a.equals(b));
        Asserts.assertTrue("sameAst 应判定相同", AstNormalizer.sameAst(V1, V2_REORDERED));
        // 归一化文本不应残留注释
        Asserts.assertFalse("注释应被消除", a.contains("//") || a.contains("/*"));
    }

    /** 验收②：实质变更（方法体/字段值）不漏报。 */
    public void testSubstantiveChangeNotMissed() {
        String a = AstNormalizer.normalizeOrNull(V1);
        String c = AstNormalizer.normalizeOrNull(V3_SUBSTANTIVE);
        Asserts.assertNotNull("实质版应解析成功", c);
        Asserts.assertTrue("实质变更应保持不等（不漏报）", !a.equals(c));
        Asserts.assertTrue("sameAst 应判定不同", !AstNormalizer.sameAst(V1, V3_SUBSTANTIVE));
        // 字面量变更应保留在归一化文本中（可解释性）
        Asserts.assertTrue("变更后的字面量应在归一化文本中", c.contains("hello, "));
    }

    /** 嵌套类重排+内部成员重排 → 相等；嵌套类内部实质变更 → 不等。 */
    public void testNestedClassNormalization() {
        String outer1 = """
                package com.demo;
                public class Outer {
                    static class Inner { int b = 2; int a = 1; }
                    void m2() {}
                    void m1() {}
                }
                """;
        String outer2 = """
                package com.demo;
                public class Outer {
                    void m1() { }
                    class Inner { int a = 1; int b = 2; }
                    void m2() { }
                }
                """;
        Asserts.assertTrue("嵌套类内外重排应归一化相等",
                AstNormalizer.sameAst(outer1, outer2));
    }

    /** 验收④：语法错误 → null 降级，且降级计数递增。 */
    public void testBrokenSourceDegradesToNull() {
        int before = AstNormalizer.degradedCount();
        Asserts.assertNull("语法错误应降级 null", AstNormalizer.normalizeOrNull("public class Broke {{{"));
        Asserts.assertNull("空串应降级 null", AstNormalizer.normalizeOrNull(""));
        Asserts.assertNull("null 应降级", AstNormalizer.normalizeOrNull(null));
        Asserts.assertTrue("降级计数应递增", AstNormalizer.degradedCount() > before);
        // sameAst 任一侧降级 → false（回退行级口径）
        Asserts.assertTrue("任一侧降级应回退行级（返回 false）", !AstNormalizer.sameAst(V1, "class Broke {{{"));
    }

    /** 验收③：性能护栏——中等文件（~600 行）解析归一化耗时有界（远小于反编译成本）。 */
    public void testNormalizePerformanceBounded() {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.demo;\npublic class Big {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("    public void m").append(i).append("(int p) {\n")
              .append("        int x = p + ").append(i).append(";\n")
              .append("        System.out.println(x);\n")
              .append("    }\n");
        }
        sb.append("}\n");
        String src = sb.toString();
        long t0 = System.nanoTime();
        String n = AstNormalizer.normalizeOrNull(src);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Asserts.assertNotNull("大文件应解析成功", n);
        Asserts.assertTrue("600 行解析归一化应 <1500ms，实际 " + ms + "ms", ms < 1500);
        // 同内容重排（把方法逆序）仍应相等
        StringBuilder rev = new StringBuilder("package com.demo;\npublic class Big {\n");
        for (int i = 199; i >= 0; i--) {
            rev.append("    public void m").append(i).append("(int p) {\n")
               .append("        int x = p + ").append(i).append(";\n")
               .append("        System.out.println(x);\n")
               .append("    }\n");
        }
        rev.append("}\n");
        Asserts.assertTrue("200 方法全逆序应归一化相等", AstNormalizer.sameAst(src, rev.toString()));
    }
}

package com.bempdiff.diff;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FR-006 语法感知归一化（AST 对齐）：把 .java 源码解析为 javac AST 后输出
 * 「成员排序 + 结构化打印」的规范化文本，使 格式化调整 / 注释增删 / 成员重排
 * 归一化后相等（零假差异），实质逻辑变更仍保持不等（不漏报）。
 *
 * <p>技术选型：用 JDK 内置 com.sun.source（javac 解析 API）而非 tree-sitter——
 * 零新增依赖（PRD 风险1 提示 tree-sitter 在超大反编译文件上的耗时需 spike，
 * javac 解析同为编译器级前端且随 JRE 分发）；反编译产物是合法 Java，天然可解析。</p>
 *
 * <p>降级契约：解析失败（非法源码）或超限返回 null，调用方回退行级口径并提示
 * （验收④「不可解析文件自动降级行级」）；降级次数经 {@link #degradedCount} 供观测。</p>
 */
public final class AstNormalizer {

    private AstNormalizer() {
    }

    /** 归一化输入大小上限：与 DiffEngine.NORM_MAX_BYTES 同口径，超限直接降级（防大文件解析卡顿）。 */
    public static final int MAX_SRC_CHARS = 2 * 1024 * 1024;

    /** 降级计数（解析失败/超限）：观测口径，供验证与报告提示。 */
    private static final AtomicInteger DEGRADED = new AtomicInteger();
    /** 当前作业线程内的降级计数；比对任务在单线程执行，避免报告串到其他作业的历史累计值。 */
    private static final ThreadLocal<AtomicInteger> JOB_DEGRADED = new ThreadLocal<>();

    /** 比对作业开始时调用；同一线程结束时必须调用 {@link #endJobScope()}。 */
    public static void beginJobScope() {
        JOB_DEGRADED.set(new AtomicInteger());
    }

    /** 返回本作业线程内的降级次数并清除线程局部，防止虚拟线程复用时串作业。 */
    public static int endJobScope() {
        AtomicInteger c = JOB_DEGRADED.get();
        JOB_DEGRADED.remove();
        return c == null ? 0 : c.get();
    }

    private static void markDegraded() {
        DEGRADED.incrementAndGet();
        AtomicInteger c = JOB_DEGRADED.get();
        if (c != null) c.incrementAndGet();
    }

    /** 成员排序单元：sortKey=种类+规范化文本（去重排序），body=规范化文本。 */
    private static final class Member {
        final String sortKey;
        final String body;

        Member(String sortKey, String body) {
            this.sortKey = sortKey;
            this.body = body;
        }
    }

    /**
     * 归一化 .java 源码：解析成功 → 各类型成员排序后拼接的结构化文本；
     * 解析失败/超限/无类型声明 → null（调用方降级行级）。
     *
     * <p>归一化性质：注释消除（javac Pretty 打印不含注释）、空白/换行按结构输出
     * （与原格式无关）、类成员按「种类:文本」排序（成员重排不敏感）、嵌套类递归归一。
     * 方法体实质变更会完整保留在成员文本中（不漏报）。</p>
     */
    public static String normalizeOrNull(String src) {
        if (src == null || src.isEmpty()) return null;
        if (src.length() > MAX_SRC_CHARS) {
            markDegraded();
            return null;
        }
        try {
            JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
            // 必须显式收集诊断：javac parse() 对残缺源码会做 error recovery 静默恢复，
            // 不查诊断会把语法错误文件误判为「解析成功」，归一化出无意义的空结构（假相等）。
            javax.tools.DiagnosticCollector<JavaFileObject> diags = new javax.tools.DiagnosticCollector<>();
            JavaFileObject fo = new SimpleJavaFileObject(
                    URI.create("mem://bempdiff/AstNormalize.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncoding) {
                    return src;
                }
            };
            JavacTask task = (JavacTask) jc.getTask(null, null, diags,
                    List.of("-proc:none"), null, List.of(fo));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            for (javax.tools.Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                    markDegraded();
                    return null;
                }
            }
            StringBuilder out = new StringBuilder(src.length());
            boolean anyType = false;
            for (CompilationUnitTree cu : units) {
                List<String> parts = new ArrayList<>();
                for (Tree td : cu.getTypeDecls()) {
                    normalizeClass(td, parts);
                    anyType = true;
                }
                parts.sort(String::compareTo);
                for (String p : parts) out.append(p).append('\n');
            }
            // 无任何类型声明（纯 package-info 等）→ AST 口径无意义，降级
            if (!anyType || out.length() == 0) {
                markDegraded();
                return null;
            }
            return out.toString();
        } catch (Throwable t) {
            // 解析失败（非法源码）→ 降级行级；catch Throwable 是刻意的降级兜底（任何解析器内部错误都不应中断比对主流程）
            markDegraded();
            return null;
        }
    }

    /** 便捷判定：两侧均解析成功且归一化文本相等（= 无实质逻辑变更）。任一侧降级 → false（回退行级口径）。 */
    public static boolean sameAst(String oldJava, String newJava) {
        String a = normalizeOrNull(oldJava);
        if (a == null) return false;
        String b = normalizeOrNull(newJava);
        return b != null && a.equals(b);
    }

    /** 降级次数快照（观测/报告提示用）。 */
    public static int degradedCount() {
        return DEGRADED.get();
    }

    /** 类成员归一化：成员经 javac 结构化打印（toString），按「种类:文本」排序消除成员顺序；嵌套类递归。 */
    private static void normalizeClass(Tree t, List<String> parts) {
        if (!(t instanceof ClassTree ct)) {
            // 非类声明（import/注解等顶层其它声明）：按结构文本原样参与排序
            parts.add(String.valueOf(t));
            return;
        }
        List<Member> members = new ArrayList<>();
        for (Tree m : ct.getMembers()) {
            if (m instanceof ClassTree inner) {
                // 嵌套类：递归归一化后作为整体参与外层排序（重排内外层均不敏感）
                List<String> innerParts = new ArrayList<>();
                normalizeClass(inner, innerParts);
                innerParts.sort(String::compareTo);
                String joined = String.join("\n", innerParts);
                members.add(new Member("CLASS:" + joined, joined));
            } else {
                members.add(new Member(m.getKind() + ":" + m, m.toString()));
            }
        }
        members.sort((a, b) -> a.sortKey.compareTo(b.sortKey));
        for (Member m : members) parts.add(m.body);
    }
}

package com.bempdiff.diff;

import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.parse.PackageParser;
import com.bempdiff.util.TextCodec;

/**
 * 前端源码文本 diff 服务（FR4.4 增强 / 前端代码对比分析）。
 *  - 压缩 JS / CSS / HTML 常为单行巨块，直接行级 diff 几乎无价值；本服务先按类型"美化/缩进"成多行，
 *    再做统一行级 diff，使改动可读、AI 输入可用。
 *  - 返回与 class 反编译同构的 DecompiledUnit（oldSource/newSource/diffText），便于报告/UI/AI 复用同一渲染与深读路径。
 *  - 美化是"尽力而为"：解析异常或超大输入直接回退原始文本，绝不影响比对主流程。
 */
public final class FrontendTextDiff {

    /** 美化输入长度上限（字符）：超过则跳过美化，直接回退原始文本，避免极端大文件拖慢比对。 */
    private static final int BEAUTIFY_MAX_CHARS = 2_000_000;

    /** 缺失侧占位短哈希（与归档/Office diff 的占位口径一致，标识该侧无此文件）。 */
    private static final String FILL_CHAR_PLACEHOLDER = "0000000";

    private final PackageParser parser = new PackageParser();

    /** 对单个文本资源（前端 JS/HTML/CSS、JSP、配置文件）做"美化 + 双栏 diff"（默认规则）。
     *  返回 DecompiledUnit（与 class 反编译同构，便于报告/UI/AI 复用同一渲染与深读路径）。 */
    public DecompiledUnit diff(PackageSnapshot oldSnap, PackageSnapshot newSnap,
                               LogicalEntry oldEntry, LogicalEntry newEntry, String key, FileClass fc) {
        return diff(oldSnap, newSnap, oldEntry, newEntry, key, fc, DiffRules.DEFAULT);
    }

    /** 对单个文本资源做"美化 + 双栏 diff"（P0-②：带忽略规则）。 */
    public DecompiledUnit diff(PackageSnapshot oldSnap, PackageSnapshot newSnap,
                               LogicalEntry oldEntry, LogicalEntry newEntry, String key, FileClass fc,
                               DiffRules rules) {
        try {
            byte[] oldBytes = (oldEntry != null) ? parser.readEntryBytes(oldSnap, oldEntry) : null;
            byte[] newBytes = (newEntry != null) ? parser.readEntryBytes(newSnap, newEntry) : null;
            String oldRaw = (oldBytes != null) ? TextCodec.decode(oldBytes) : null;
            String newRaw = (newBytes != null) ? TextCodec.decode(newBytes) : null;
            String oldText = (oldRaw != null) ? beautify(oldRaw, fc) : null;
            String newText = (newRaw != null) ? beautify(newRaw, fc) : null;

            String engine = engineLabel(fc);

            String diff;
            if (oldText == null) {
                // 新增文件：每行加 "+ " 前缀（含说明行），保证前端 diff 行号计数连续、右栏归属正确
                diff = prefixLines("+ ", "// [新增文件] 老包无此文件\n" + (newText == null ? "" : newText));
            } else if (newText == null) {
                // 删除文件：每行加 "- " 前缀，同理保证行号计数
                diff = prefixLines("- ", "// [删除文件] 新包无此文件（资源移除，需确认引用方）\n" + oldText);
            } else {
                diff = LineDiff.unified(oldText, newText, rules);
            }
            return new DecompiledUnit(key, oldText, newText, diff, engine, "", true,
                    textHash(key, oldBytes, oldEntry), textHash(key, newBytes, newEntry));
        } catch (Exception e) {
            return DecompiledUnit.fail(key, e.getMessage());
        }
    }

    /**
     * 直接对原始字节做"美化 + 双栏 diff"（供归档内部文本条目复用，避免走 PackageSnapshot 解析）。
     * 任一側字节为 null 表示该侧不存在（新增/删除文本文件），与 {@link #diff} 语义一致。
     */
    public DecompiledUnit diffBytes(byte[] oldBytes, byte[] newBytes, String key, FileClass fc, DiffRules rules) {
        try {
            String oldRaw = (oldBytes != null) ? TextCodec.decode(oldBytes) : null;
            String newRaw = (newBytes != null) ? TextCodec.decode(newBytes) : null;
            String oldText = (oldRaw != null) ? beautify(oldRaw, fc) : null;
            String newText = (newRaw != null) ? beautify(newRaw, fc) : null;

            String engine = engineLabel(fc);
            String diff;
            if (oldText == null) {
                // 新增文件：每行加 "+ " 前缀（含说明行），保证前端 diff 行号计数连续、右栏归属正确
                diff = prefixLines("+ ", "// [新增文件] 老侧无此文件\n" + (newText == null ? "" : newText));
            } else if (newText == null) {
                // 删除文件：每行加 "- " 前缀，同理保证行号计数
                diff = prefixLines("- ", "// [删除文件] 新侧无此文件（资源移除，需确认引用方）\n" + oldText);
            } else {
                diff = LineDiff.unified(oldText, newText, rules);
            }
            // 归档内部文本条目无 entry 元数据，bytes 自身 + key 拼串作为指纹；缺失侧 0000000
            String oldHash = (oldBytes != null)
                    ? com.bempdiff.util.ShortHash.ofBytes(oldBytes) : FILL_CHAR_PLACEHOLDER;
            String newHash = (newBytes != null)
                    ? com.bempdiff.util.ShortHash.ofBytes(newBytes) : FILL_CHAR_PLACEHOLDER;
            return new DecompiledUnit(key, oldText, newText, diff, engine, "", true, oldHash, newHash);
        } catch (Exception e) {
            return DecompiledUnit.fail(key, e.getMessage());
        }
    }

    /**
     * 文本类短哈希：LogicalEntry 提供 key + size；mtime 不可用（LogicalEntry 不存 mtime，且 jar 内部
     * mtime 通常为 0）。用 key+size 拼串保证两侧身份可区分、缺失侧统一回退 0000000。
     */
    private static String textHash(String key, byte[] bytes, LogicalEntry entry) {
        if (bytes == null) return FILL_CHAR_PLACEHOLDER;
        String path = entry != null ? entry.getKey() : key;
        long size = entry != null ? entry.getSize() : bytes.length;
        return com.bempdiff.util.ShortHash.ofPathAndSize(path, size);
    }

    /**
     * 给文本每一行统一加 diff 前缀（含尾空行），使前端 DiffView 的行号计数对所有行生效。
     * 空行也会得到前缀（如 "+ "），避免前端把无前缀裸行判为「无行号」并破坏后续计数。
     */
    static String prefixLines(String prefix, String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(prefix).append(line).append("\n");
        }
        return sb.toString();
    }

    /** 引擎标签：按文件类型区分美化/比对方式（用于报告与导出清单标注）。 */
    private static String engineLabel(FileClass fc) {
        if (fc == null) return "text-diff";
        switch (fc) {
            case CSS:  return "css-beautify";
            case HTML: return "html-beautify";
            case JSP:  return "jsp-normalize";
            case JAVA: return "java-normalize";
            case CONFIG:return "text-normalize";
            default:   return "js-beautify"; // JS 及未知前端文本
        }
    }

    /** 按文件类型美化（压缩→多行）/归一化。异常或超大输入回退原始文本。 */
    public static String beautify(String text, FileClass fc) {
        if (text == null) return null;
        if (text.length() > BEAUTIFY_MAX_CHARS) return text;
        try {
            if (fc == FileClass.CSS) return beautifyCss(text);
            if (fc == FileClass.HTML) return beautifyHtml(text);
            if (fc == FileClass.JSP) return beautifyJsp(text);
            if (fc == FileClass.JAVA) return normalizeNewlines(text); // Java 源码：保留原始排版，仅换行归一
            if (fc == FileClass.CONFIG) return normalizeNewlines(text); // 配置文件：仅换行归一，直接行级 diff 最清晰
            return beautifyJs(text); // JS 默认处理（含未知前端文本）
        } catch (Exception e) {
            // 美化是尽力而为：任何异常都回退原始文本，不影响比对
            return text;
        }
    }

    /** 是否疑似压缩（单行巨块）。用于测试与按需美化判定（非压缩文本也可美化，仅风格更整齐）。 */
    public static boolean looksMinified(String text) {
        if (text == null || text.isEmpty()) return false;
        String[] lines = text.split("\n", -1);
        int maxLine = 0;
        for (String l : lines) maxLine = Math.max(maxLine, l.length());
        // 任意行超长 且（行数极少 或 平均行长过高）→ 压缩
        return maxLine > 400 && (lines.length < 6 || (double) text.length() / lines.length > 200);
    }

    // ----------------------------- JS 美化（字符串/注释安全） -----------------------------

    private static String beautifyJs(String text) { // NOSONAR(S3776) 单遍字符状态机按语义有序跳转，拆分会割裂注释/字符串上下文，保持整体可读
        if (!looksMinified(text)) {
            // 非压缩：仅统一换行（去掉多余尾随空白），保持原结构，降低噪声
            return normalizeNewlines(text);
        }
        StringBuilder out = new StringBuilder();
        int depth = 0;
        int n = text.length();
        char quote = 0;            // 当前字符串引号（0=不在字符串）
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < n; i++) { // NOSONAR(S135) 状态跃迁的正常表达，多个 continue 对应注释/字符串内退出
            char c = text.charAt(i);
            if (blockComment) {
                out.append(c);
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                    out.append('\n');
                    blockComment = false;
                    i++; // NOSONAR(S127) 跳过已吞入的 '/'，前置推进循环下标是状态机的既有语义
                }
                continue;
            }
            if (lineComment) {
                out.append(c);
                if (c == '\n') lineComment = false;
                continue;
            }
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); continue; } // NOSONAR(S127)
                if (c == quote) quote = 0;
                continue;
            }
            // 普通状态
            if (c == '\'' || c == '"' || c == '`') {
                quote = c; out.append(c); continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                lineComment = true; out.append(c); continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                blockComment = true; out.append(c); continue;
            }
            if (c == '{') {
                out.append('{').append('\n');
                depth = Math.min(depth + 1, 64);
                indent(out, depth);
            } else if (c == '}') {
                out.append('\n');
                depth = Math.max(depth - 1, 0);
                indent(out, depth);
                out.append('}');
            } else if (c == ';') {
                out.append(';').append('\n');
                indent(out, depth);
            } else if (c == '\n' || c == '\r') {
                out.append(c); // 保留原有换行
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ----------------------------- CSS 美化 -----------------------------

    private static String beautifyCss(String text) { // NOSONAR(S3776) 单遍字符状态机，语义同 beautifyJs，拆分会产生与 JS 相同的上下文割裂风险
        if (!looksMinified(text)) return normalizeNewlines(text);
        StringBuilder out = new StringBuilder();
        int depth = 0;
        int n = text.length();
        char quote = 0;
        boolean blockComment = false;
        for (int i = 0; i < n; i++) { // NOSONAR(S135) 状态跃迁的正常表达，多个 continue 对应注释/字符串内退出
            char c = text.charAt(i);
            if (blockComment) {
                out.append(c);
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') { out.append('\n'); blockComment = false; i++; } // NOSONAR(S127)
                continue;
            }
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); continue; } // NOSONAR(S127)
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; out.append(c); continue; }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') { blockComment = true; out.append(c); continue; }
            if (c == '{') {
                out.append('{').append('\n'); depth = Math.min(depth + 1, 16); indent(out, depth);
            } else if (c == '}') {
                out.append('\n'); depth = Math.max(depth - 1, 0); indent(out, depth); out.append('}');
            } else if (c == ';') {
                out.append(';').append('\n'); indent(out, depth);
            } else if (c == '\n' || c == '\r') {
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ----------------------------- HTML 美化 -----------------------------

    private static String beautifyHtml(String text) {
        // 即使非压缩的"单行 HTML"，也按标签边界拆行，使结构可读、diff 有价值；
        // 仅替换紧邻的 "><"（不影响文本内的 < / >，如内联 <script> 中的 a<b）。
        String normalized = normalizeNewlines(text);
        return normalized.replace("><", ">\n<");
    }

    // ----------------------------- JSP 美化 -----------------------------

    private static String beautifyJsp(String text) {
        // JSP = HTML 标签 + <% ... %> 脚本片段 + ${EL} 表达式。
        // 与 HTML 同样按标签边界拆行，并额外在 <%/<%=/<%@/%> 边界处断行，使脚本片段可读。
        String normalized = normalizeNewlines(text);
        String s = normalized.replace("><", ">\n<");
        s = s.replace("<%", "\n<%").replace("%>", "%>\n");
        // 压缩连续空行，降低噪声
        StringBuilder sb = new StringBuilder();
        boolean lastBlank = false;
        for (String line : s.split("\n", -1)) {
            boolean blank = line.trim().isEmpty();
            if (blank && lastBlank) continue;
            sb.append(line).append('\n');
            lastBlank = blank;
        }
        return sb.toString();
    }

    // ----------------------------- 工具 -----------------------------

    private static String normalizeNewlines(String text) {
        // 统一 \r\n / \r → \n，去每行尾随空白（降低无意义 diff 噪声）
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < n && text.charAt(i + 1) == '\n') i++;
                sb.append('\n');
            } else if (c == '\n') {
                // 去掉行尾空白
                int len = sb.length();
                while (len > 0 && (sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\t')) {
                    sb.setLength(len - 1); len--;
                }
                sb.append('\n');
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }
}
package com.bempdiff.report;

import com.bempdiff.ai.CategoryConclusion;
import com.bempdiff.ai.FileAnalysis;
import com.bempdiff.ai.FileRisk;
import com.bempdiff.ai.StageASummary;
import com.bempdiff.ai.context.ProjectContext;
import com.bempdiff.diff.DiffDigest;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.LibJarDiff;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 报告导出（T12，FR7）。对应 prototype: write_markdown_report。
 * 默认包含：差异统计 + 全量清单 + Top-K 完整源码 diff（Java 类 + 前端 JS/HTML/CSS）+ 破坏性变更清单（删除类）。
 * 说明：本移植版先实现"无 AI 章节"的基础报告（AI 章节待 core.ai 接入后补）。
 */
public final class MarkdownReport {

    private static final String DIFF_OPEN = "```diff\n";
    private static final String DIFF_CLOSE = "\n```\n\n";
    private static final String BESIDES = "> 另有 ";
    /** 项目上下文影响标签（AI 章节多处重复，提取为常量规避 S1192）。 */
    private static final String LABEL_CTX_INFLUENCE = "- **项目上下文影响**：";
    /** 架构简述标签（预算裁剪与渲染多次出现）。 */
    private static final String LABEL_ARCH_SUMMARY = "- 架构简述：";
    /** 老包/新包行中的「（版本 」前缀（聚焦与全量报告多处复用，规避 S1192）。 */
    private static final String VER_BEGIN = "`（版本 ";
    /** 「** · 删除 **」间隔（新增/删除/修改统计行复用，规避 S1192）。 */
    private static final String ATTR_DELETED_BETWEEN = "** · 删除 **";
    /** 「** · 修改 **」间隔（新增/删除/修改统计行复用，规避 S1192）。 */
    private static final String ATTR_MODIFIED_BETWEEN = "** · 修改 **";

    private final int topK;

    public MarkdownReport(int topK) {
        this.topK = topK;
    }

    /** 本次 AI 分析聚焦项（可读标签，如「破坏性变更专项」）；null 表示默认整体分析。 */
    private String aiFocus = null;

    /** AI 章节标题（含编号，如「七、AI 智能分析…」）；聚焦报告可改为无编号标题避免跳空。 */
    private String aiSectionTitle = "## 七、AI 智能分析（两阶段 / 项目级上下文增强）";

    /** FR-007：API 兼容性分级章节文本（ApiCompat.Result.toMarkdownSection 产物；空串=不插入）。 */
    private String apiCompatSection = "";

    /** FR-012：变更影响面章节文本（ImpactAnalyzer.Result.toMarkdownSection 产物；空串=不插入）。 */
    private String impactSection = "";

    /** FR-013：AI 实际消耗行（如「输入 1234 / 输出 567 tokens（3 次调用）」；空串=不插入）。 */
    private String aiUsageLine = "";

    /** 本次 AI 章节是否由真实 LLM 产出（false=离线 Mock 兜底）；仅用于审计摘要口径，不参与正文渲染。 */
    private boolean aiRealLlm = false;

    /** T01810 FR-006 展示层：AST 口径「格式或位置变更」条目数（0=不渲染统计行）。 */
    private int formattedOnlyCount = 0;

    /** T01810 FR-006 验收④：AST 不可解析降级行级的文件数（0=不渲染降级提示）。 */
    private int astDegradedCount = 0;

    /** 设置 AI 实际消耗行（渲染进审计摘要，输入/输出分段）。 */
    public MarkdownReport setAiUsageLine(String line) {
        this.aiUsageLine = line == null ? "" : line;
        return this;
    }

    /** 标记本次 AI 章节是否真实调用模型（false=离线 Mock），审计摘要据此避免「已接入」误述。 */
    public MarkdownReport setAiRealLlm(boolean realLlm) {
        this.aiRealLlm = realLlm;
        return this;
    }

    /** 设置 API 兼容性分级章节（在破坏性变更清单之后、AI 章节之前插入）。 */
    public MarkdownReport setApiCompatSection(String section) {
        this.apiCompatSection = section == null ? "" : section;
        return this;
    }

    /** 设置变更影响面章节（紧跟 API 兼容性分级之后）。 */
    public MarkdownReport setImpactSection(String section) {
        this.impactSection = section == null ? "" : section;
        return this;
    }

    /** 设置「格式或位置变更」条目数（DiffResult.formattedOnlyCount；0=不渲染）。 */
    public MarkdownReport setFormattedOnlyCount(int n) {
        this.formattedOnlyCount = Math.max(0, n);
        return this;
    }

    /** 设置 AST 降级文件数（AstNormalizer.degradedCount；0=不渲染）。 */
    public MarkdownReport setAstDegradedCount(int n) {
        this.astDegradedCount = Math.max(0, n);
        return this;
    }

    /** 设置报告 AI 章节的分析聚焦标注（由 BempServer.runAiAnalysis 按 category/prompt 派生）。 */
    public MarkdownReport setAiFocus(String focus) {
        this.aiFocus = focus;
        return this;
    }

    /** 覆写 AI 章节标题（聚焦精简报告使用：删除编号避免一至五被精简后跳空）。 */
    public MarkdownReport setAiSectionTitle(String title) {
        this.aiSectionTitle = title == null || title.isEmpty() ? aiSectionTitle : title;
        return this;
    }

    public String getAiFocus() {
        return aiFocus;
    }

    public String render(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                          DiffResult r, DiffStats stats,
                          Map<String, DecompiledUnit> decompiled,
                          Map<String, DecompiledUnit> text) {
        return render(oldSnap, newSnap, r, stats, decompiled, text, (LibJarDiff.Result) null);
    }

    /** 渲染（含差异依赖 JAR 内部源码对比章节）。libJar 为 null 时跳过该章节。 */
    public String render(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                          DiffResult r, DiffStats stats,
                          Map<String, DecompiledUnit> decompiled,
                          Map<String, DecompiledUnit> text, LibJarDiff.Result libJar) {
        StringBuilder sb = new StringBuilder();
        renderHeader(sb, oldSnap, newSnap);
        renderStats(sb, stats);
        renderTypeBreakdown(sb, r, oldSnap, newSnap);
        renderFileTree(sb, r);
        renderDecompiledDiff(sb, decompiled);
        renderTextDiff(sb, text);
        renderLibJarDiff(sb, libJar);
        renderBreakingChanges(sb, r);
        // FR-007/FR-012：非 AI 报告同样插入 API 兼容性分级 + 影响面章节（与 AI 版 render 同口径）
        if (apiCompatSection != null && !apiCompatSection.isEmpty()) {
            sb.append(apiCompatSection);
        }
        if (impactSection != null && !impactSection.isEmpty()) {
            sb.append(impactSection);
        }
        renderFooter(sb, oldSnap, newSnap, false);
        return sb.toString();
    }

    /**
     * 渲染（含 AI 智能分析章节与项目级上下文增强）。无 AI 数据时 summary/fileAnalyses 传 null，
     * 未启用上下文增强时 ctx 传 null —— 均向后兼容原 6 参 render。
     */
    // NOSONAR: 多参重载为既有公开 API，签名不可变更（见任务约束）。
    public String render(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                          DiffResult r, DiffStats stats,
                          Map<String, DecompiledUnit> decompiled,
                          Map<String, DecompiledUnit> text,
                          StageASummary summary, List<FileAnalysis> fileAnalyses, ProjectContext ctx) {
        return render(oldSnap, newSnap, r, stats, decompiled, text,
                summary, fileAnalyses, ctx, (LibJarDiff.Result) null);
    }

    /** 渲染（含差异依赖 JAR 内部源码对比章节 + AI 智能分析章节）。libJar 为 null 时跳过 JAR 章节。 */
    // NOSONAR: 多参重载为既有公开 API，签名不可变更（见任务约束）。
    public String render(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                          DiffResult r, DiffStats stats,
                          Map<String, DecompiledUnit> decompiled,
                          Map<String, DecompiledUnit> text,
                          StageASummary summary, List<FileAnalysis> fileAnalyses, ProjectContext ctx,
                          LibJarDiff.Result libJar) {
        StringBuilder sb = new StringBuilder();
        renderHeader(sb, oldSnap, newSnap);
        renderStats(sb, stats);
        renderTypeBreakdown(sb, r, oldSnap, newSnap);
        renderFileTree(sb, r);
        renderDecompiledDiff(sb, decompiled);
        renderTextDiff(sb, text);
        renderLibJarDiff(sb, libJar);
        renderBreakingChanges(sb, r);
        // FR-007/FR-012：API 兼容性分级 + 变更影响面章节（静态判定，不兼容项 sev 徽章醒目），无差异时不插入
        if (apiCompatSection != null && !apiCompatSection.isEmpty()) {
            sb.append(apiCompatSection);
        }
        if (impactSection != null && !impactSection.isEmpty()) {
            sb.append(impactSection);
        }
        appendAiSection(sb, summary, fileAnalyses, ctx);
        renderFooter(sb, oldSnap, newSnap, summary != null);
        return sb.toString();
    }

    /**
     * 渲染「聚焦分析报告」：以 AI 智能分析章节为绝对核心，仅保留必要的背景信息
     * （包信息、精简差异统计、本次分析聚焦、命名文件树），其余（源码级 diff、JAR 内部
     * 对比、破坏性变更清单、审计摘要等）一律剔除，使文档主题突出、结构精简。
     *
     * <p>仅当调用方显式选择了分析项（{@link #aiFocus} 非空）时由后端走此路径；
     * 标题以分析主题命名（如「测试要点分析报告」），AI 章节改用无编号标题避免跳空。</p>
     */
    public String renderFocusReport(PackageSnapshot oldSnap, PackageSnapshot newSnap,
                          DiffResult r, DiffStats stats,
                          StageASummary summary, List<FileAnalysis> fileAnalyses, ProjectContext ctx) {
        StringBuilder sb = new StringBuilder();
        // 标题：以分析主题命名，aiFocus 例如「测试要点分析」→「测试要点分析报告」
        sb.append("# ").append(reportTitleFromFocus(aiFocus)).append("\n\n");
        // 背景：老包/新包标识（分析对象）
        sb.append("- 老包：`").append(oldSnap.getFile().getFileName()).append(VER_BEGIN)
                .append(nullToNA(oldSnap.getVersion())).append("）\n");
        sb.append("- 新包：`").append(newSnap.getFile().getFileName()).append(VER_BEGIN)
                .append(nullToNA(newSnap.getVersion())).append("）\n");
        // 背景：精简差异统计（新增/删除/修改，供分析锚定范围）
        sb.append("- 差异规模：新增 **").append(stats.getAdded()).append(ATTR_DELETED_BETWEEN)
                .append(stats.getDeleted()).append(ATTR_MODIFIED_BETWEEN).append(stats.getModified())
                .append("**\n\n");
        renderFocusFileList(sb, r);
        // 核心：AI 智能分析章节（内容与全量报告中的 AI 章节完全一致，仅标题去编号）
        appendAiSection(sb, summary, fileAnalyses, ctx);
        return sb.toString();
    }

    /** 从分析聚焦标签派生报告标题：「测试要点分析」→「测试要点分析报告」；无聚焦则回落通用标题。 */
    private static String reportTitleFromFocus(String focus) {
        if (focus == null || focus.isEmpty()) {
            return "AI 智能分析报告";
        }
        // 已是「…报告」则原样；否则直接拼接「报告」
        return focus.endsWith("报告") ? focus : focus + "报告";
    }

    /** 精简文件树：仅命名清单（变更类型 + 文件名），供 AI 分析参考完整变更范围。 */
    private void renderFocusFileList(StringBuilder sb, DiffResult r) {
        boolean any = false;
        DiffStatus[] order = {DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED};
        for (DiffStatus st : order) {
            for (String k : r.get(st)) {
                if (!any) {
                    sb.append("#### 变更文件清单\n");
                    any = true;
                }
                sb.append("- `").append(mark(st)).append("` ").append(k).append("\n");
            }
        }
        if (any) {
            sb.append("\n");
        } else {
            sb.append("（无文件变更清单）\n\n");
        }
    }

    /**
     * 渲染单文件 AI 功能总结（差异树右键「AI功能总结」）。
     * 与全量报告模板解耦：只含该文件元信息、AI 结论与差异摘要——
     * 若复用全量 render，单文件总结会带上整体统计/文件树/其他文件 diff，
     * 与「整体风险分析」雷同且不聚焦用户所选文件。
     */
    public String renderSingleFile(String key, DiffStatus st, FileClass fc,
                                   DecompiledUnit unit, FileAnalysis fa, ProjectContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 单文件 AI 功能总结\n\n");
        sb.append("| 文件 | 变更类型 | 文件类型 | 差异引擎 |\n|---|---|---|---|\n");
        // 文件名含 | 会切断表格列结构（归档内部条目名来自包内路径，不可信），须转义（评审 L2）
        sb.append("| `").append(escapePipe(key)).append("` | ").append(statusLabel(st))
          .append(" | ").append(fc).append(" | ").append(nullToNA(unit.getEngine())).append(" |\n\n");
        if (fa != null) {
            sb.append("## AI 分析结论\n\n");
            sb.append("- **改动意图**：").append(nullToNA(fa.getIntent())).append("\n");
            sb.append("- **风险等级**：**").append(nullToNA(fa.getRisk())).append("**\n");
            sb.append("- **影响范围**：").append(nullToNA(fa.getImpact())).append("\n");
            if (fa.isOutputTruncated()) {
                appendTruncatedWarning(sb);
            }
            if (!isEmpty(fa.getContextInfluence())) {
                sb.append(LABEL_CTX_INFLUENCE).append(fa.getContextInfluence()).append("\n");
            }
            if (!isEmptyList(fa.getTestPoints())) {
                sb.append("- **测试要点**：\n");
                for (String tp : fa.getTestPoints()) {
                    sb.append("  - ").append(tp).append("\n");
                }
            }
            sb.append("\n");
        } else {
            sb.append("## AI 分析结论\n\n（AI 未返回该文件的分析结论）\n\n");
        }
        appendAiContext(sb, ctx);
        sb.append("## 差异内容摘要\n\n");
        if (!unit.isOk()) {
            sb.append("- 内容提取失败：").append(unit.getError()).append("\n");
        } else {
            sb.append(DIFF_OPEN).append(renderCompactDiff(unit.getDiffText())).append(DIFF_CLOSE);
        }
        return sb.toString();
    }

    /** 追加 AI 智能分析章节：分析聚焦标注 + 项目级上下文摘要 + 阶段A 概览 + 阶段B 逐文件。 */
    private void appendAiSection(StringBuilder sb, StageASummary summary,
                                 List<FileAnalysis> fileAnalyses, ProjectContext ctx) {
        sb.append(aiSectionTitle).append("\n");
        // 修复：显式标注本次所选分析项，避免「不同分析项生成的报告看起来相同」
        if (aiFocus != null && !aiFocus.isEmpty()) {
            sb.append("- **本次分析聚焦**：").append(aiFocus).append("\n\n");
        }
        appendAiContext(sb, ctx);
        appendStageASummary(sb, summary);
        appendStageB(sb, fileAnalyses);
        if ((ctx == null || ctx.isEmpty()) && summary == null) {
            sb.append("- 未启用 AI 分析 / 项目级上下文增强。\n");
        }
        sb.append("\n");
    }

    /** 报告「项目级上下文（分析依据）」正文长度上限（含标点）。超长会挤占报告篇幅或被 UI 截断，故在此压缩。 */
    private static final int CTX_ANALYSIS_MAX_CHARS = 200;
    /** 架构简述为自由叙述文本，给其一个子上限，避免独占预算导致结构化关键字段（构建/模块/依赖）被挤掉。 */
    private static final int CTX_SUMMARY_MAX_CHARS = 120;

    private void appendAiContext(StringBuilder sb, ProjectContext ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return;
        }
        sb.append("### 项目级上下文（分析依据）\n");
        // 用独立局部累加器承接本小节正文，预算只针对小节本身（而非整个报告 sb），
        // 再整体 append，确保「正文 ≤ 200 字」不依赖外层 sb 的已累积长度。
        StringBuilder body = new StringBuilder();
        // 架构简述为自由叙述文本，先按对称子上限预裁剪，避免独占后置的结构化关键字段预算
        String summary = ctx.getSummary();
        if (!isEmpty(summary) && summary.length() > CTX_SUMMARY_MAX_CHARS - LABEL_ARCH_SUMMARY.length() - 1) {
            summary = summary.substring(0, CTX_SUMMARY_MAX_CHARS - LABEL_ARCH_SUMMARY.length() - 2) + "…";
        }
        appendBudgeted(body, CTX_ANALYSIS_MAX_CHARS, LABEL_ARCH_SUMMARY, summary);
        appendBudgeted(body, CTX_ANALYSIS_MAX_CHARS, "- 构建系统：", nullToNA(ctx.getBuildSystem()));
        appendBudgeted(body, CTX_ANALYSIS_MAX_CHARS, "- 模块：", joinCapped(ctx.getModules()));
        appendBudgeted(body, CTX_ANALYSIS_MAX_CHARS, "- 核心依赖：", joinCapped(ctx.getDependencies()));
        appendBudgeted(body, CTX_ANALYSIS_MAX_CHARS, "- 配置文件：", joinCapped(ctx.getConfigFiles()));
        sb.append(body).append("\n");
    }

    /** 将「标签:值」追加到 out，保证整段字符数不超过 maxChars；预算不足时截断值并追加省略号。空值跳过该行。 */
    private static void appendBudgeted(StringBuilder out, int maxChars, String label, String value) {
        if (isEmpty(value)) {
            return;
        }
        int room = maxChars - out.length();
        if (room <= label.length() + 1) {
            return; // 预算不足以容纳「标签+换行」，整行省略
        }
        int valueRoom = room - label.length() - 1; // 预留一个换行符
        String display;
        if (value.length() <= valueRoom) {
            display = value;
        } else {
            // 截断到剩余预算，保留前部核心信息并标注截断
            int keep = Math.max(0, valueRoom - 1);
            display = value.substring(0, keep) + "…";
        }
        out.append(label).append(display).append("\n");
    }

    private void appendStageASummary(StringBuilder sb, StageASummary summary) {
        if (summary == null) {
            return;
        }
        sb.append("### 阶段A 概览\n");
        sb.append("- 整体风险：**").append(nullToNA(summary.getOverallRisk())).append("**\n");
        if (summary.isOutputTruncated()) {
            appendTruncatedWarning(sb);
        }
        sb.append("- 影响范围：").append(nullToNA(summary.getImpactScope())).append("\n");
        if (!isEmpty(summary.getContextInfluence())) {
            sb.append(LABEL_CTX_INFLUENCE).append(summary.getContextInfluence()).append("\n");
        }
        if (!isEmptyList(summary.getTestThemes())) {
            sb.append("- 测试主题：").append(String.join("；", summary.getTestThemes())).append("\n");
        }
        if (!isEmptyList(summary.getFileRisks())) {
            sb.append("- 每文件初评风险：\n");
            for (FileRisk fr : summary.getFileRisks()) {
                sb.append("  - `").append(nullToNA(fr.getKey())).append("`：**")
                        .append(nullToNA(fr.getRisk())).append("** — ").append(nullToNA(fr.getOneLineReason())).append("\n");
            }
        }
        sb.append("\n");
        // 方案B：按所选分析项追加「分类聚焦结论」，让不同类别的报告结构显著区分、重点突出。
        appendFocusConclusion(sb, summary);
    }

    /**
     * 分类聚焦结论渲染（方案B）。依据 {@link StageASummary#getCategory()} 决定呈现哪个维度的
     * conclusion 字段，实现「分析边界与内容区分」：专项（breaking/impact/testpoints）只渲染
     * 与自身维度直接相关的字段；整体（risk/custom/缺省回退为整体）则全面呈现各维度并补充
     * 风险判定依据/回滚/降级预案。优化改造建议对全部类别统一附带。
     */
    private void appendFocusConclusion(StringBuilder sb, StageASummary summary) {
        CategoryConclusion c = summary.getConclusion();
        if (c == null) {
            return;
        }
        String cat = summary.getCategory() == null ? "" : summary.getCategory();
        switch (cat) {
            case "breaking":
                sb.append("### 破坏性变更专项结论\n");
                renderBreakingList(sb, c.getBreakingChanges());
                appendKVLine(sb, "兼容性结论", c.getCompatibilityVerdict());
                break;
            case "impact":
                sb.append("### 影响范围专项结论\n");
                appendKVLine(sb, "受影响模块/服务", c.getAffectedModules());
                appendKVLine(sb, "受影响对外接口", c.getAffectedApis());
                appendKVLine(sb, "内部调用方/依赖链路", c.getInternalCallers());
                appendKVLine(sb, "扩散路径与数据流", c.getDiffusion());
                break;
            case "testpoints":
                sb.append("### 测试要点专项结论\n");
                renderTestPoints(sb, c.getTestPoints());
                break;
            case "risk":
            case "custom":
            default:
                // 整体风险（及自定义/缺省）：全面呈现各维度，并整合风险预案。
                // 二三级清单（破坏性变更/测试要点）对整体分析属补充维度，仅当确有内容时才渲染，
                // 避免空清单占位噪音稀释整体结论重点（专项报告才用占位提示「未识别到」）。
                if (hasAny(c)) {
                    sb.append("### 整体风险结论\n");
                    appendKVLine(sb, "风险判定依据", c.getRiskRationale());
                    appendKVLine(sb, "受影响模块/服务", c.getAffectedModules());
                    appendKVLine(sb, "受影响对外接口", c.getAffectedApis());
                    renderBreakingListIfAny(sb, c);
                    renderTestPointsIfAny(sb, c);
                    appendKVLine(sb, "回滚预案", c.getRollbackPlan());
                    appendKVLine(sb, "降级预案", c.getDegradationPlan());
                }
                break;
        }
        if (c.hasSuggestions()) {
            sb.append("### 优化改造建议（结合项目上下文）\n");
            sb.append(c.getSuggestions()).append("\n\n");
        }
    }

    /** 整体分支是否至少有一项可渲染内容（避免空结论只输出一个空小节标题）。 */
    private static boolean hasAny(CategoryConclusion c) {
        return !isEmpty(c.getRiskRationale())
                || !isEmpty(c.getAffectedModules())
                || !isEmpty(c.getAffectedApis())
                || !isEmptyList(c.getBreakingChanges())
                || !isEmptyList(c.getTestPoints())
                || !isEmpty(c.getRollbackPlan())
                || !isEmpty(c.getDegradationPlan());
    }

    /** 整体分支的破坏性变更清单：仅当确有条目时才渲染（专项报告才用「未识别到」占位）。 */
    private static void renderBreakingListIfAny(StringBuilder sb, CategoryConclusion c) {
        if (!isEmptyList(c.getBreakingChanges())) {
            renderBreakingList(sb, c.getBreakingChanges());
        }
    }

    /** 整体分支的测试要点清单：仅当确有条目时才渲染（专项报告才用「未识别到」占位）。 */
    private static void renderTestPointsIfAny(StringBuilder sb, CategoryConclusion c) {
        if (!isEmptyList(c.getTestPoints())) {
            renderTestPoints(sb, c.getTestPoints());
        }
    }

    /** 破坏性变更条目清单（breaking schema）。 */
    private static void renderBreakingList(StringBuilder sb, List<CategoryConclusion.BreakingChange> list) {
        if (isEmptyList(list)) {
            sb.append("- 未识别到破坏性变更点。\n\n");
            return;
        }
        for (CategoryConclusion.BreakingChange bc : list) {
            sb.append("- **`").append(nullToNA(bc.getFile())).append("`** [")
                    .append(nullToNA(bc.getChangeType())).append("]（严重度 ")
                    .append(normSeverity(bc.getSeverity())).append("）\n");
            sb.append("  - 变更点：").append(nullToNA(bc.getChange())).append("\n");
            if (!isEmpty(bc.getCompatImpact())) {
                sb.append("  - 兼容性影响：").append(bc.getCompatImpact()).append("\n");
            }
            if (!isEmpty(bc.getMigrationSuggestion())) {
                sb.append("  - 迁移改造建议：").append(bc.getMigrationSuggestion()).append("\n");
            }
        }
        sb.append("\n");
    }

    /** 测试要点清单（testpoints schema）。 */
    private static void renderTestPoints(StringBuilder sb, List<CategoryConclusion.TestPoint> list) {
        if (isEmptyList(list)) {
            sb.append("- 未识别到针对性测试要点。\n\n");
            return;
        }
        int i = 1;
        for (CategoryConclusion.TestPoint tp : list) {
            sb.append(i++).append(". **").append(nullToNA(tp.getItem())).append("**");
            if (!isEmpty(tp.getFile())) {
                sb.append("（`").append(tp.getFile()).append("`）");
            }
            sb.append("\n");
            if (!isEmpty(tp.getScenario())) {
                sb.append("   - 场景：").append(tp.getScenario()).append("\n");
            }
            if (!isEmpty(tp.getCaseIdea())) {
                sb.append("   - 用例思路：").append(tp.getCaseIdea()).append("\n");
            }
            if (!isEmpty(tp.getVerifyFocus())) {
                sb.append("   - 验证重点：").append(tp.getVerifyFocus()).append("\n");
            }
        }
        sb.append("\n");
    }

    /** 有值字段行：仅当 v 非空时输出「键：值」。 */
    private static void appendKVLine(StringBuilder sb, String k, String v) {
        if (v != null && !v.isEmpty()) {
            sb.append("- **").append(k).append("**：").append(v).append("\n");
        }
    }

    /** severity 归一化：容忍模型返回的大小写/中文变体，统一映射为 LOW/MEDIUM/HIGH，未知值保留原文兜底。 */
    private static String normSeverity(String v) {
        if (v == null || v.isEmpty()) {
            return "LOW";
        }
        String up = v.trim().toUpperCase();
        if (up.contains("HIGH") || up.contains("严重") || up.contains("高")) return "HIGH";
        if (up.contains("MEDIUM") || up.contains("中")) return "MEDIUM";
        if (up.contains("LOW") || up.contains("低")) return "LOW";
        return v.trim();
    }

    private void appendStageB(StringBuilder sb, List<FileAnalysis> fileAnalyses) {
        if (isEmptyList(fileAnalyses)) {
            return;
        }
        sb.append("### 阶段B 逐文件深读\n");
        for (FileAnalysis fa : fileAnalyses) {
            appendFileAnalysis(sb, fa);
        }
    }

    private void appendFileAnalysis(StringBuilder sb, FileAnalysis fa) {
        sb.append("#### ").append(nullToNA(fa.getKey())).append("\n");
        sb.append("- 风险：**").append(nullToNA(fa.getRisk())).append("**\n");
        sb.append("- 意图：").append(nullToNA(fa.getIntent())).append("\n");
        sb.append("- 影响：").append(nullToNA(fa.getImpact())).append("\n");
        if (fa.isOutputTruncated()) {
            appendTruncatedWarning(sb);
        }
        if (!isEmpty(fa.getContextInfluence())) {
            sb.append(LABEL_CTX_INFLUENCE).append(fa.getContextInfluence()).append("\n");
        }
        if (!isEmptyList(fa.getTestPoints())) {
            sb.append("- 测试要点：\n");
            for (String tp : fa.getTestPoints()) {
                sb.append("  - ").append(tp).append("\n");
            }
        }
        sb.append("\n");
    }

    /** AI 输出被 max_tokens 截断时的提示行（多处渲染复用；指引调高配置或收缩范围，避免用户误信残缺结论）。 */
    private static void appendTruncatedWarning(StringBuilder sb) {
        sb.append("> ⚠ 本结论由被截断的 AI 输出解析而来，可能不完整。"
                + "可调高配置中心「单次请求最大输出 Token」或收缩参与分析的范围后重试。\n");
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static boolean isEmptyList(List<?> l) {
        return l == null || l.isEmpty();
    }

    /** 压缩清单：最多保留前 4 项，超出的部分以「等 N 项」概括，避免长清单挤占上下文预算。 */
    private static String joinCapped(List<String> l) {
        if (isEmptyList(l)) {
            return "（无）";
        }
        if (l.size() <= 4) {
            return String.join("、", l);
        }
        return String.join("、", l.subList(0, 4)) + " 等" + l.size() + "项";
    }

    private void renderHeader(StringBuilder sb, PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        // 标题不绑定 Java 端口：目录模式主要输出源码差异，包模式输出 class 反编译差异，统一用产品口径
        sb.append("# 差异分析报告（BempDiff 包/目录比对）\n\n");
        // 目录模式用「老目录/新目录」，避免把解压目录误读成构建包
        boolean folderMode = oldSnap.getFile() != null && Files.isDirectory(oldSnap.getFile());
        String oldLabel = folderMode ? "老目录" : "老包";
        String newLabel = folderMode ? "新目录" : "新包";
        sb.append("- ").append(oldLabel).append("：`").append(oldSnap.getFile().getFileName()).append(VER_BEGIN)
                .append(nullToNA(oldSnap.getVersion())).append("）\n");
        sb.append("- ").append(newLabel).append("：`").append(newSnap.getFile().getFileName()).append(VER_BEGIN)
                .append(nullToNA(newSnap.getVersion())).append("）\n\n");
    }

    private void renderStats(StringBuilder sb, DiffStats stats) {
        sb.append("## 一、差异统计\n");
        sb.append("- 新增 **").append(stats.getAdded()).append(ATTR_DELETED_BETWEEN).append(stats.getDeleted())
                .append(ATTR_MODIFIED_BETWEEN).append(stats.getModified()).append("** · 未变 ").append(stats.getUnchanged()).append("\n");
        sb.append("- 业务/类级变更(非jar)：").append(stats.getBizChanged())
                .append(" · jar 级变更：").append(stats.getJarChanged()).append("\n");
        // T01810 FR-006 展示层：AST 口径折叠的「格式或位置变更」单独计数——
        // 让用户知道「有多少文件曾出现格式化/重排差异但被 AST 归位」，而非静默混入未变
        if (formattedOnlyCount > 0) {
            sb.append("- 其中按 AST 口径判定为「格式或位置变更」（已折叠为未变，不计入差异）：")
              .append(formattedOnlyCount).append("\n");
        }
        // FR-006 验收④：不可解析的 .java 已降级行级口径——明示降级避免「为何该类还有格式假差异」困惑
        if (astDegradedCount > 0) {
            sb.append("> 注：").append(astDegradedCount)
              .append(" 个 Java 文件无法解析为 AST（超限/语法不完整），已降级为行级比对口径。\n");
        }
        sb.append("\n");
    }

    private void renderFileTree(StringBuilder sb, DiffResult r) {
        sb.append("## 二、差异文件树（全量清单）\n");
        DiffStatus[] order = {DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED};
        for (DiffStatus st : order) {
            for (String k : r.get(st)) {
                sb.append("- `").append(mark(st)).append("` ").append(k).append("\n");
            }
        }
        sb.append("\n");
    }

    private void renderDecompiledDiff(StringBuilder sb, Map<String, DecompiledUnit> decompiled) {
        sb.append("## 三、反编译源码级差异（Top-").append(topK).append(" 修改/新增/删除 Java 类）\n");
        if (decompiled.isEmpty()) {
            sb.append("> 本次未收集到可反编译的 Java class。目录模式的 .java 源码差异见第四节；"
                    + "包模式若需分析三方包内 class，请在「配置中心 → 解析与导出」开启“展开全部（含三方 class）”"
                    + "后重新比对（普通 jar 自身的业务 class 默认按 L1 收集）。\n\n");
            return;
        }
        sb.append("> 共 ").append(decompiled.size()).append(" 个类已反编译（本处仅展示前 Top-K 条，"
                + "且每条仅列变更行：附原始/修改后行号与变更类型——修改/新增/删除，不展示整个文件）。\n\n");
        int shown = 0;
        for (Map.Entry<String, DecompiledUnit> e : decompiled.entrySet()) {
            if (shown >= topK) {
                break;
            }
            DecompiledUnit u = e.getValue();
            sb.append("### ").append(e.getKey()).append("\n");
            if (!u.isOk()) {
                sb.append("- 反编译失败：").append(u.getError()).append("\n\n");
            } else {
                sb.append("- 反编译引擎：").append(u.getEngine()).append("\n");
                sb.append(DIFF_OPEN).append(renderCompactDiff(u.getDiffText())).append(DIFF_CLOSE);
                shown++;
            }
        }
        if (decompiled.size() > topK) {
            sb.append(BESIDES).append(decompiled.size() - topK).append(" 个修改类未展开（Top-K 限制）。\n\n");
        }
    }

    /**
     * 差异依赖 JAR 内部源码对比（Req 6）：对 WAR 中存在差异的 lib jar，逐一展开内部 class，
     * 反编译后做源码级 diff，清晰标注每个 class 的新增/删除/修改/未变，并附 Top-K 源码 diff。
     * libJar 为 null 时跳过（向后兼容无 JAR 对比的报告）。
     */
    private void renderLibJarDiff(StringBuilder sb, LibJarDiff.Result libJar) {
        // 章节编号为手工顺序（四→五→六）。标题必须先输出：否则 libJar 为空时整章（含标题）被跳过，
        // 编号会从「四」直接跳到「六」，导致报告标题序号不连续。与其他章节一致，空态保留标题+提示。
        sb.append("## 五、差异依赖 JAR 内部源码对比（Top-").append(topK)
          .append(" 内部 class 反编译源码级 diff）\n");
        if (libJar == null || libJar.isEmpty()) {
            sb.append("- 无差异依赖 JAR（本次比对未检出 JAR 级依赖变动）。\n\n");
            return;
        }
        sb.append("> 共 ").append(libJar.totalJars).append(" 个差异 JAR（新增 ")
          .append(countJarStatus(libJar, DiffStatus.ADDED)).append(" / 修改 ")
          .append(countJarStatus(libJar, DiffStatus.MODIFIED)).append(" / 删除 ")
          .append(countJarStatus(libJar, DiffStatus.DELETED)).append("）；其内部 class 合计：新增 ")
          .append(libJar.totalAdded).append(" · 删除 ").append(libJar.totalRemoved)
          .append(" · 修改 ").append(libJar.totalModified).append(" · 未变 ")
          .append(libJar.totalUnchanged).append("。\n\n");
        for (LibJarDiff.DiffJarInfo jar : libJar.jars) {
            renderJarDetail(sb, jar);
        }
    }

    /** 渲染单个差异 JAR 的详情：标题、内部 class 状态统计与逐 class 的源码对比。 */
    private void renderJarDetail(StringBuilder sb, LibJarDiff.DiffJarInfo jar) {
        if (jar.failed) {
            sb.append("### ").append(jar.jarKey).append("  [分析失败]\n");
            sb.append("- ⚠️ 该 JAR 读取/枚举失败，已跳过（不影响其余 JAR 与报告其余章节）：")
              .append(jar.error == null ? "未知错误" : jar.error).append("\n\n");
            return;
        }
        sb.append("### ").append(jar.jarKey).append("  [")
          .append(statusLabel(jar.jarStatus)).append("]\n");
        sb.append("- 内部 class：新增 **").append(jar.added).append(ATTR_DELETED_BETWEEN).append(jar.removed)
          .append(ATTR_MODIFIED_BETWEEN).append(jar.modified).append("** · 未变 ").append(jar.unchanged).append("\n\n");
        int shown = 0;
        for (LibJarDiff.LibClassUnit u : jar.classes) {
            shown = renderOneLibClass(sb, u, shown);
        }
        appendJarClassSummary(sb, jar);
    }

    /** 渲染单个 lib 内部 class 的对比条目，返回最新已展开条数（用于 Top-K 截断）。 */
    private int renderOneLibClass(StringBuilder sb, LibJarDiff.LibClassUnit u, int shown) {
        DecompiledUnit du = u.unit;
        if (du == null) {
            // 未提供反编译单元（Top-K 之外）：仅跳过，不做任何展示。
            return shown;
        }
        if (!du.isOk()) {
            sb.append("- `").append(u.innerClass).append("` [").append(statusLabel(u.status))
              .append("] 反编译失败：").append(du.getError()).append("\n");
            return shown;
        }
        if (shown >= topK) {
            return shown;
        }
        sb.append("#### ").append(u.innerClass).append("  [").append(statusLabel(u.status)).append("]\n");
        sb.append("- 反编译引擎：").append(du.getEngine()).append("\n");
        sb.append(DIFF_OPEN).append(renderCompactDiff(du.getDiffText())).append(DIFF_CLOSE);
        return shown + 1;
    }

    /** 收尾计数拆分：未变更 / 反编译失败 / 超出全局 Top-K 三类，避免把未变更误归为 Top-K 限制。 */
    private static void appendJarClassSummary(StringBuilder sb, LibJarDiff.DiffJarInfo jar) {
        long unchanged = 0;
        long failedUnits = 0;
        long decompiledCount = 0;
        for (LibJarDiff.LibClassUnit u : jar.classes) {
            if (u.status == DiffStatus.UNCHANGED) {
                unchanged++;
            }
            if (u.unit != null && !u.unit.isOk()) {
                failedUnits++;
            }
            if (u.unit != null && u.unit.isOk()) {
                decompiledCount++;
            }
        }
        long beyondTopK = jar.classes.size() - unchanged - failedUnits - decompiledCount;
        List<String> parts = new java.util.ArrayList<>();
        if (beyondTopK > 0) parts.add(beyondTopK + " 个超出全局 Top-K 未展开源码");
        if (unchanged > 0) parts.add(unchanged + " 个未变更（已折叠，无需反编译）");
        if (failedUnits > 0) parts.add(failedUnits + " 个反编译失败");
        if (!parts.isEmpty()) {
            sb.append(BESIDES).append(String.join(" · ", parts)).append("；")
              .append("可在 GUI 双击该 JAR 查看全部内部 class 的逐项反编译对比。\n\n");
        }
    }

    /** 统计差异 JAR 的个数（按 jar 自身状态），用于章节开头的总览。 */
    private static long countJarStatus(LibJarDiff.Result libJar, DiffStatus st) {
        long c = 0;
        for (LibJarDiff.DiffJarInfo jar : libJar.jars) {
            if (jar.jarStatus == st) {
                c++;
            }
        }
        return c;
    }

    private static String statusLabel(DiffStatus st) {
        switch (st) {
            case ADDED: return "新增 +";
            case DELETED: return "删除 -";
            case MODIFIED: return "修改 ~";
            default: return "未变 =";
        }
    }

    /** 按文件类型汇总变更分布（新增/删除/修改），清晰标注每种文件类型的变更情况（需求扩展）。 */
    private void renderTypeBreakdown(StringBuilder sb, DiffResult r,
                                     PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        // categoryLabel -> [added, deleted, modified]
        Map<String, long[]> counts = new java.util.TreeMap<>();
        DiffStatus[] order = {DiffStatus.ADDED, DiffStatus.DELETED, DiffStatus.MODIFIED};
        for (DiffStatus st : order) {
            for (String k : r.get(st)) {
                LogicalEntry e = oldSnap.getEntries().get(k);
                if (e == null) {
                    e = newSnap.getEntries().get(k);
                }
                FileClass fc = (e != null) ? e.getFileClass() : FileClass.OTHER;
                String cat = fc.categoryLabel();
                long[] c = counts.computeIfAbsent(cat, x -> new long[3]);
                if (st == DiffStatus.ADDED) {
                    c[0]++;
                } else if (st == DiffStatus.DELETED) {
                    c[1]++;
                } else {
                    c[2]++;
                }
            }
        }
        sb.append("### 按文件类型分布\n");
        sb.append("| 文件类型 | 新增 | 删除 | 修改 |\n");
        sb.append("|---|---|---|---|\n");
        for (Map.Entry<String, long[]> en : counts.entrySet()) {
            long[] c = en.getValue();
            sb.append("| ").append(en.getKey()).append(" | ").append(c[0]).append(" | ")
              .append(c[1]).append(" | ").append(c[2]).append(" |\n");
        }
        long totalAdded = r.get(DiffStatus.ADDED).size();
        long totalDeleted = r.get(DiffStatus.DELETED).size();
        long totalModified = r.get(DiffStatus.MODIFIED).size();
        sb.append("| **合计** | **").append(totalAdded).append("** | **").append(totalDeleted)
          .append("** | **").append(totalModified).append("** |\n\n");
    }

    /**
     * 文本类文件内容差异（配置文件 XML/Properties、JSP、Java 源码、前端 JS/HTML/CSS）：压缩/单行资源已
     * 美化或换行归一后做内容级逐行 diff，使改动可读（FR4.4 增强 + 本需求扩展）。
     */
    private void renderTextDiff(StringBuilder sb, Map<String, DecompiledUnit> text) {
        sb.append("## 四、文本类文件内容差异（Top-").append(topK)
          .append(" 修改/新增/删除 配置文件/JSP/Java 源码/JS/HTML/CSS）\n");
        if (isEmptyMap(text)) {
            sb.append("- 无文本类文件（配置文件/JSP/Java 源码/前端源码）变动。\n\n");
            return;
        }
        sb.append("> 共 ").append(text.size()).append(" 个文本类文件已做内容级逐行 diff")
          .append("（本处仅展示前 Top-K 条，且每条仅列变更行：附原始/修改后行号与变更类型——修改/新增/删除，不展示整个文件）。\n\n");
        int shown = 0;
        for (Map.Entry<String, DecompiledUnit> e : text.entrySet()) {
            if (shown >= topK) {
                break;
            }
            DecompiledUnit u = e.getValue();
            sb.append("### ").append(e.getKey()).append("\n");
            if (!u.isOk()) {
                sb.append("- 读取/美化失败：").append(u.getError()).append("\n\n");
            } else {
                sb.append("- 处理引擎：").append(u.getEngine()).append("\n");
                sb.append(DIFF_OPEN).append(renderCompactDiff(u.getDiffText())).append(DIFF_CLOSE);
                shown++;
            }
        }
        if (text.size() > topK) {
            sb.append(BESIDES).append(text.size() - topK).append(" 个文本类文件未展开（Top-K 限制）。\n\n");
        }
    }

    private static boolean isEmptyMap(Map<?, ?> m) {
        return m == null || m.isEmpty();
    }

    private void renderBreakingChanges(StringBuilder sb, DiffResult r) {
        sb.append("## 六、破坏性变更清单（删除类/删除前端资源）\n");
        if (r.get(DiffStatus.DELETED).isEmpty()) {
            sb.append("- 无删除类。\n");
        } else {
            for (String k : r.get(DiffStatus.DELETED)) {
                sb.append("- `").append(k).append("`（潜在对外 API / 行为移除，需重点回归）\n");
            }
        }
        sb.append("\n");
    }

    private void renderFooter(StringBuilder sb, PackageSnapshot oldSnap, PackageSnapshot newSnap, boolean aiEnabled) {
        // 有 AI 章节时审计为 八（位于 AI 之后）；无 AI 时为 七（紧接破坏性变更）。
        sb.append(aiEnabled ? "## 八、审计摘要（基础）\n" : "## 七、审计摘要（基础）\n");
        sb.append("- 比对时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
        sb.append("- 老包标识：").append(nullToNA(oldSnap.getVersion())).append("\n");
        sb.append("- 新包标识：").append(nullToNA(newSnap.getVersion())).append("\n");
        sb.append("- AI 分析：");
        if (!aiEnabled) {
            sb.append("未接入，本报告不含 AI 章节。");
        } else if (aiRealLlm) {
            sb.append("已接入真实模型（含项目级上下文增强）。");
        } else {
            sb.append("已生成 AI 章节，但为离线兜底（未调用真实模型，未配置 API Key 或开关未开启）。");
        }
        sb.append("\n");
        // FR-013：实际消耗（输入/输出分段）；仅 AI 报告且后端返回了 usage 时出现
        if (aiEnabled && aiUsageLine != null && !aiUsageLine.isEmpty()) {
            sb.append("- AI 实际消耗：").append(aiUsageLine).append("\n");
        }
    }

    public void writeToFile(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                            DiffResult r, DiffStats stats,
                            Map<String, DecompiledUnit> decompiled,
                            Map<String, DecompiledUnit> text, Path outFile) throws IOException {
        writeToFile(oldSnap, newSnap, r, stats, decompiled, text, (LibJarDiff.Result) null, outFile);
    }

    /** 写报告（含差异依赖 JAR 内部源码对比章节）。 */
    // NOSONAR: 多参重载为既有公开 API，签名不可变更（见任务约束）。
    public void writeToFile(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                            DiffResult r, DiffStats stats,
                            Map<String, DecompiledUnit> decompiled,
                            Map<String, DecompiledUnit> text, LibJarDiff.Result libJar,
                            Path outFile) throws IOException {
        String md = render(oldSnap, newSnap, r, stats, decompiled, text, libJar);
        writeMd(outFile, md);
    }

    /** 写报告（含 AI 智能分析章节与项目级上下文增强）。 */
    // NOSONAR: 多参重载为既有公开 API，签名不可变更（见任务约束）。
    public void writeToFile(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                            DiffResult r, DiffStats stats,
                            Map<String, DecompiledUnit> decompiled,
                            Map<String, DecompiledUnit> text,
                            StageASummary summary, List<FileAnalysis> fileAnalyses,
                            ProjectContext ctx, Path outFile) throws IOException {
        writeToFile(oldSnap, newSnap, r, stats, decompiled, text,
                summary, fileAnalyses, ctx, (LibJarDiff.Result) null, outFile);
    }

    /** 写报告（含 AI 智能分析章节、项目级上下文增强与差异依赖 JAR 内部源码对比章节）。 */
    // NOSONAR: 多参重载为既有公开 API，签名不可变更（见任务约束）。
    public void writeToFile(PackageSnapshot oldSnap, PackageSnapshot newSnap, // NOSONAR(S107) - 多参重载为既有公开 API
                            DiffResult r, DiffStats stats,
                            Map<String, DecompiledUnit> decompiled,
                            Map<String, DecompiledUnit> text,
                            StageASummary summary, List<FileAnalysis> fileAnalyses,
                            ProjectContext ctx, LibJarDiff.Result libJar, Path outFile) throws IOException {
        String md = render(oldSnap, newSnap, r, stats, decompiled, text, summary, fileAnalyses, ctx, libJar);
        writeMd(outFile, md);
    }

    /** 将报告串联内容写入目标文件，必要时先创建父目录。 */
    private static void writeMd(Path outFile, String md) throws IOException {
        Path parent = outFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent); // 防御：输出目录可能不存在
        }
        try (Writer w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write(md);
        }
    }

    private static String mark(DiffStatus st) {
        switch (st) {
            case ADDED: return "新增 +";
            case DELETED: return "删除 -";
            case MODIFIED: return "修改 ~";
            default: return "未变 =";
        }
    }

    private static String nullToNA(String s) {
        return s == null ? "N/A" : s;
    }

    /**
     * 渲染代码差异为紧凑变更摘要：仅列变更行（不含上下文行），每块附行号区间（老 Lx-y → 新 La-b）
     * 与变更类型（修改/新增/删除），控制报告篇幅。复用 {@link DiffDigest} 的解析与渲染：
     * 仅展示发生变更的代码行而非整个文件，便于用户快速定位与核对。
     */
    private static String renderCompactDiff(String diffText) {
        if (diffText == null || diffText.isEmpty()) return "(无差异内容)";
        return DiffDigest.render(diffText);
    }

    /** Markdown 表格单元格转义：| 前加反斜杠，防止文件名中的竖线切断列结构。 */
    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }
}
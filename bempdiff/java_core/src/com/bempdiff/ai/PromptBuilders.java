package com.bempdiff.ai;

import com.bempdiff.ai.context.ProjectContext;
import com.bempdiff.config.AiConfig;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.model.DecompiledUnit;
import com.bempdiff.model.FileClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段 prompt 构造工具（离线可验证，不触网）。对应详细设计 §5.4 数据契约。
 *  - 阶段A：差异清单 + 每个改动文件的"摘要 diff"（截断策略：反编译源码取类签名/关键方法/前 N 行，文本取变更片段）
 *  - 阶段B：仅发完整 diffText（已脱敏）；按文件类型（Java 类 / 前端 JS/HTML/CSS）切换领域措辞
 *  - 脱敏：公网模型模式正则擦除疑似证件号/密钥字面量（详见 sanitize）
 */
public final class PromptBuilders {

    private PromptBuilders() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /** 阶段标题前缀（buildStageB 多处重复，提取为常量规避 S1192）。 */
    private static final String FILE_HEADER = "## 文件：";

    /**
     * 阶段B 单文件 diff 内容长度上限（字符）。
     * 超限截断：防止「整文件重写 / 超大型文件 / 压缩资源美化后」把单次 LLM 请求与成本预估撑爆
     * （此前实测：整文件 diff 无上限，15 个文件即可累计 1.3 亿字符 ≈ 6700 万 token 预估）。
     * 预估与真实调用共用本 builder，改此处即可同时收紧成本闸门预估与真实请求体。
     */
    public static final int STAGE_B_DIFF_CHAR_CAP = 60_000;

    /**
     * 阶段A 单文件 diff 摘要的字符上限。
     *
     * <p><b>缺陷背景（实测事故）</b>：阶段A 原先<b>只有行数截断</b>（{@code stageAFileSampleLines}），
     * 挡不住「单行超长」——压缩 JS / 整文件重写 / 反编译长行，单行即可达数万字符。
     * 叠加 {@code stageATopK} 个文件后 prompt 膨胀到 195504 tokens，
     * 触发供应商 <code>HTTP 400 number of input tokens has exceeded max_prompt_tokens (131072)</code>。
     * 阶段B 早有 {@link #STAGE_B_DIFF_CHAR_CAP} 字符护栏，阶段A 缺失 → 护栏不对称是本次事故根因。
     */
    public static final int STAGE_A_FILE_CHAR_CAP = 8_000;

    /**
     * 阶段A 全部文件摘要的字符总量上限。
     * 与 {@link #STAGE_B_DIFF_CHAR_CAP} 同量级，保证最坏情况（{@code stageATopK} 个文件全部超长）
     * 也远低于主流模型 128K 上下文窗口，杜绝「成本闸门确认通过、请求仍被模型拒收」。
     */
    public static final int STAGE_A_TOTAL_CHAR_CAP = 60_000;

    /** 阶段A 追加下一个文件所需的最小剩余预算：低于此值停止追加，避免产出零碎无意义的截断片段。 */
    private static final int STAGE_A_MIN_FILE_BUDGET = 500;

    /** 阶段A prompt：让模型产出 JSON（整体风险/影响/测试主题 + 每文件初评）。 */
    public static String buildStageA(DiffResult diff, Map<String, DecompiledUnit> decompiled, AiConfig cfg) {
        return buildStageA(diff, decompiled, cfg, null);
    }

    /** 阶段A prompt（项目级上下文增强）：ctx 非空时注入「项目级上下文」章节并要求产出 contextInfluence。 */
    public static String buildStageA(DiffResult diff, Map<String, DecompiledUnit> decompiled,
                                     AiConfig cfg, ProjectContext ctx) {
        return buildStageABody(diff, decompiled, cfg, ctx, null);
    }

    /** 阶段A prompt（携带顶层统计口径）：头部「差异统计」用 topStats（与折叠树一致），
     *  文件摘要/深读仍按叶子级（嵌套 zip 内部也要被分析）。topStats 为空则回退叶子计数。 */
    public static String buildStageA(DiffResult diff, Map<String, DecompiledUnit> decompiled,
                                     AiConfig cfg, ProjectContext ctx, DiffStats topStats) {
        return buildStageABody(diff, decompiled, cfg, ctx, topStats);
    }

    private static String buildStageABody(DiffResult diff, Map<String, DecompiledUnit> decompiled,
                                          AiConfig cfg, ProjectContext ctx, DiffStats topStats) {
        StringBuilder p = new StringBuilder();
        // 差异文件过多（超出 stageATopK 概览上限）时：剥离逐文件初评（fileRisks），
        // 只要求整体结论——文件多时模型输出的 fileRisks 数组很容易撞输出 token 上限被腰斩，
        // 且被省略的文件本就无法逐文件初评，输出不全的初评反而稀释整体结论（实测事故）。
        int totalChanged = collectChangedFiles(diff).size();
        boolean compactMode = totalChanged > cfg.getStageATopK();
        appendDiffHeader(p, diff, cfg, topStats, compactMode, totalChanged);
        appendContextSection(p, ctx);
        appendApiCompatSection(p, decompiled);
        appendFileSummaries(p, diff, decompiled, cfg);
        return sanitize(p.toString(), cfg);
    }

    /**
     * FR-007：注入 API 兼容性静态分级（验收②「结论进入 AI 分析上下文」）。
     * 分级摘要由 AiAnalyzer default stageA 以合成条目 __api_compat__ 携带——该 key 不在
     * diff 差异清单里，appendFileSummaries（按 diff keys 遍历）不会重复呈现；此处显式消费。
     */
    private static void appendApiCompatSection(StringBuilder p, Map<String, DecompiledUnit> decompiled) {
        if (decompiled == null) return;
        DecompiledUnit u = decompiled.get("__api_compat__");
        if (u != null && u.getDiffText() != null && !u.getDiffText().isEmpty()) {
            p.append(u.getDiffText());
        }
    }

    /** 注入项目级上下文章节（ctx 为空则不注入，行为与旧版一致）。 */
    private static void appendContextSection(StringBuilder p, ProjectContext ctx) {
        if (ctx == null || ctx.isEmpty()) return;
        p.append(ctx.toPromptSection()).append("\n");
        p.append("请在上述【项目级上下文】约束下评估：架构分层 / 模块依赖 / 配置约定如何影响本次差异的")
         .append("风险与影响。输出 JSON 需额外包含字段 contextInfluence（字符串）：");
        p.append("说明项目级上下文如何修正、支撑或限定了你的结论（若无影响填\"无显著影响\"）。\n\n");
    }

    private static void appendDiffHeader(StringBuilder p, DiffResult diff, AiConfig cfg, DiffStats topStats,
                                         boolean compactMode, int totalChanged) {
        p.append("你是软件构建包（含 Java 后端与前端 JS/HTML/CSS 资源）升级的差异分析助手。下面是新/老两个构建包的差异清单，");
        p.append("请据此以 JSON 返回，字段固定为：\n");
        p.append("- `overallRisk`：整体风险等级(LOW/MEDIUM/HIGH)\n");
        p.append("- `impactScope`：影响范围描述（受影响的模块/对外接口）\n");
        p.append("- `testThemes`：全局测试要点字符串数组\n");
        if (compactMode) {
            // 文件过多：不要求逐文件初评，避免输出 JSON 超长被腰斩；引导用单文件深读补齐粒度
            p.append("- `fileRisks`：差异文件过多（共 ").append(totalChanged)
             .append(" 个），本次仅输出整体结论、不输出逐文件初评（返回空数组即可）；"
                     + "逐文件风险可右键差异树「AI功能总结」单文件深读\n\n");
        } else {
            p.append("- `fileRisks`：每个改动文件的初评数组，元素含 `key`(文件路径)、`risk`(LOW/MEDIUM/HIGH)、`oneLineReason`(一句话理由)\n\n");
        }
        p.append("## 差异统计\n");
        // 有顶层统计（与折叠树一致）优先用；否则回退叶子级计数。嵌套 zip 内部不单独计入顶部统计，
        // 但文件摘要/深读仍覆盖其内部，避免「头部聚合数误导模型」。
        int add = topStats != null ? topStats.getAdded() : diff.get(DiffStatus.ADDED).size();
        int del = topStats != null ? topStats.getDeleted() : diff.get(DiffStatus.DELETED).size();
        int mod = topStats != null ? topStats.getModified() : diff.get(DiffStatus.MODIFIED).size();
        int unch = topStats != null ? topStats.getUnchanged() : diff.get(DiffStatus.UNCHANGED).size();
        p.append("新增=").append(add)
                .append(" 删除=").append(del)
                .append(" 修改=").append(mod)
                .append(" 未变=").append(unch).append("\n\n");
        p.append("## 改动文件与 diff 摘要（截断，最多 ").append(cfg.getStageATopK()).append(" 个）\n");
        p.append("> 文件类型：`.class`=Java 后端类；`.js`=前端 JavaScript；`.html`=前端模板；`.css`=前端样式。\n\n");
    }

    /**
     * 追加全部文件摘要，并受 {@link #STAGE_A_TOTAL_CHAR_CAP} 总量护栏约束。
     * 预算耗尽时停止追加并标注被省略的文件数，引导用户改用单文件「AI功能总结」深读。
     */
    private static void appendFileSummaries(StringBuilder p, DiffResult diff,
                                            Map<String, DecompiledUnit> decompiled, AiConfig cfg) {
        List<String> files = collectChangedFiles(diff);
        // 差异文件超出概览上限时按目录分组聚合：每组的「新增/修改/删除」统计覆盖全部文件，
        // 组内只列代表文件摘要——让模型看到全量规模分布，而不是只见前 N 个文件、其余被省略
        if (files.size() > cfg.getStageATopK()) {
            appendGroupedSummaries(p, files, diff, decompiled, cfg);
            return;
        }
        int budget = STAGE_A_TOTAL_CHAR_CAP;
        int omitted = 0;
        for (int i = 0; i < files.size(); i++) {
            // 剩余预算不足以容纳一个有意义的文件摘要时停止，避免产出零碎截断内容
            if (budget < STAGE_A_MIN_FILE_BUDGET) { omitted = files.size() - i; break; }
            budget -= appendSingleFileSummary(p, files.get(i), diff, decompiled, cfg, budget);
        }
        if (omitted > 0) {
            p.append("\n... (阶段A 概览已达字符上限 ").append(STAGE_A_TOTAL_CHAR_CAP)
             .append("，另有 ").append(omitted)
             .append(" 个变更文件未纳入；完整清单见报告「变更文件」章节，"
                     + "可用差异树右键「AI功能总结」对单个文件深读)\n");
        }
    }

    /** 分组聚合路径：按目录分组，每组先输出「该组全量统计」，再在组预算内列代表文件摘要。
     *  组预算按剩余组数均摊，避免前面的大组挤占后组的全部预算；组顺序保持文件首次出现顺序。
     *  组内文件展示配额全局受 stageATopK 约束（总列出数不超 Top-K 契约），统计则覆盖全部文件。 */
    private static void appendGroupedSummaries(StringBuilder p, List<String> files, DiffResult diff,
                                               Map<String, DecompiledUnit> decompiled, AiConfig cfg) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String k : files) {
            int idx = k.lastIndexOf('/');
            String dir = idx < 0 ? "/" : k.substring(0, idx);
            groups.computeIfAbsent(dir, x -> new ArrayList<>()).add(k);
        }
        p.append("> 差异文件较多（共 ").append(files.size())
         .append(" 个），按目录分组展示各组统计与代表文件；未列出的文件也计入各组统计。\n");
        int budget = STAGE_A_TOTAL_CHAR_CAP;
        int quota = cfg.getStageATopK();
        List<String> dirs = new ArrayList<>(groups.keySet());
        int remainingGroups = dirs.size();
        for (String dir : dirs) {
            if (budget < STAGE_A_MIN_FILE_BUDGET || quota <= 0) {
                p.append("\n... (阶段A 概览已达上限，剩余目录的代表文件未列出；可用差异树右键「AI功能总结」对单个文件深读)\n");
                break;
            }
            List<String> group = groups.get(dir);
            int groupBudget = Math.max(STAGE_A_MIN_FILE_BUDGET, budget / remainingGroups);
            int groupQuota = Math.max(1, (int) Math.ceil(quota / (double) remainingGroups));
            budget -= appendGroupSummary(p, dir, group, diff, decompiled, cfg,
                    new GroupAllocation(groupBudget, groupQuota));
            quota -= groupQuota;
            remainingGroups--;
        }
    }

    /** 单目录分组的字符预算与展示配额（appendGroupSummary 的参数组）。 */
    private static final class GroupAllocation {
        final int budget;
        final int quota;

        GroupAllocation(int budget, int quota) {
            this.budget = budget;
            this.quota = quota;
        }
    }

    /** 输出单个目录分组的统计头与组内代表文件摘要（组预算/配额耗尽即停），返回实际消耗字符数。 */
    private static int appendGroupSummary(StringBuilder p, String dir, List<String> group, DiffResult diff,
                                          Map<String, DecompiledUnit> decompiled, AiConfig cfg,
                                          GroupAllocation alloc) {
        int before = p.length();
        int added = 0;
        int deleted = 0;
        int modified = 0;
        for (String k : group) {
            switch (stOf(diff, k)) {
                case ADDED: added++; break;
                case DELETED: deleted++; break;
                default: modified++;
            }
        }
        p.append("\n### [目录] ").append(dir.isEmpty() ? "/" : dir)
         .append("（共 ").append(group.size()).append(" 个变更：新增").append(added)
         .append("/修改").append(modified).append("/删除").append(deleted).append("）\n");
        int groupLeft = alloc.budget;
        int shown = 0;
        while (shown < group.size() && shown < alloc.quota && groupLeft >= STAGE_A_MIN_FILE_BUDGET) {
            groupLeft -= appendSingleFileSummary(p, group.get(shown), diff, decompiled, cfg, groupLeft);
            shown++;
        }
        if (shown < group.size()) {
            p.append("\n... (该组另有 ").append(group.size() - shown).append(" 个变更文件未列出)\n");
        }
        return p.length() - before;
    }

    /** 收集所有有变动的文件列表（新增/删除/修改），按优先级排序。 */
    private static List<String> collectChangedFiles(DiffResult diff) {
        List<String> files = new ArrayList<>();
        for (DiffStatus st : new DiffStatus[]{DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED}) {
            for (String k : diff.get(st)) files.add(k);
        }
        return files;
    }

    /**
     * 提取单文件摘要追加逻辑，降低 appendFileSummaries 的认知复杂度。
     *
     * @param budget 本文件可用的剩余字符预算（阶段A 总量护栏）
     * @return 实际消耗的字符数，供调用方扣减预算
     */
    private static int appendSingleFileSummary(StringBuilder p, String k, DiffResult diff,
                                               Map<String, DecompiledUnit> decompiled, AiConfig cfg,
                                               int budget) {
        int before = p.length();
        p.append("\n### ").append(k).append(" (").append(statusName(stOf(diff, k)));
        FileClass fc = fileClassOf(k);
        if (fc != null) p.append("，").append(frontendLabel(fc));
        p.append(")\n");
        DecompiledUnit u = decompiled.get(k);
        if (u != null && u.isOk() && u.getDiffText() != null) {
            String[] lines = u.getDiffText().split("\n");
            int lim = Math.min(lines.length, cfg.getStageAFileSampleLines());
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < lim; i++) body.append(lines[i]).append("\n");
            if (lines.length > lim) body.append("... (截断，共 ").append(lines.length).append(" 行)\n");
            String s = body.toString();
            // 字符级护栏：行数截断挡不住「单行超长」（压缩 JS / 整文件重写 / 反编译长行），必须再按字符兜底
            if (s.length() > STAGE_A_FILE_CHAR_CAP) {
                s = s.substring(0, STAGE_A_FILE_CHAR_CAP)
                        + "\n... (单文件摘要超长已截断：上限 " + STAGE_A_FILE_CHAR_CAP + " 字符)\n";
            }
            // 总量护栏：最后一个文件只追加剩余预算
            if (s.length() > budget) {
                s = s.substring(0, budget) + "\n... (已达阶段A 总字符上限)\n";
            }
            p.append(s);
        } else {
            p.append("(无源码/未纳入 Top-K)\n");
        }
        return p.length() - before;
    }

    /** 阶段B 单文件 prompt：仅发完整 diffText；按文件类型切换领域措辞（Java / 前端）。 */
    public static String buildStageB(String key, DecompiledUnit unit, FileClass fc, AiConfig cfg) {
        return buildStageB(key, unit, fc, cfg, null);
    }

    /** 阶段B 单文件 prompt（项目级上下文增强）：ctx 非空时附简要上下文并要求产出 contextInfluence。 */
    public static String buildStageB(String key, DecompiledUnit unit, FileClass fc, AiConfig cfg, ProjectContext ctx) {
        StringBuilder p = new StringBuilder();
        if (ctx != null && !ctx.isEmpty()) {
            p.append("## 项目级上下文（简要）\n").append(ctx.getSummary()).append("\n");
            p.append("请在分析本文件改动时，结合上述上下文判断其所属模块、上下游依赖与配置约定。\n\n");
        }
        if (fc != null && fc.isFrontendText()) {
            p.append("请对以下前端").append(frontendLabel(fc)).append("代码改动做逐文件深读，返回 JSON：");
            p.append("改动意图(intent)、风险等级(risk: LOW/MEDIUM/HIGH)、影响范围(impact，含对页面/交互/接口的潜在影响)、测试要点(testPoints数组)、contextInfluence（项目上下文如何影响本文件结论）。\n\n");
            p.append(FILE_HEADER).append(key).append(" （").append(frontendLabel(fc)).append("）\n");
        } else if (fc == FileClass.JSP) {
            p.append("请对以下JSP页面/标签文件改动做逐文件深读，返回 JSON：");
            p.append("改动意图(intent)、风险等级(risk: LOW/MEDIUM/HIGH)、影响范围(impact，含对页面渲染/请求处理/标签逻辑的潜在影响)、测试要点(testPoints数组)、contextInfluence（项目上下文如何影响本文件结论）。\n\n");
            p.append(FILE_HEADER).append(key).append(" （JSP 页面/标签）\n");
        } else if (fc == FileClass.JAVA) {
            p.append("请对以下 Java 源码文件改动做逐文件深读，返回 JSON：");
            p.append("改动意图(intent)、风险等级(risk: LOW/MEDIUM/HIGH)、影响范围(impact，含对调用方/接口/业务逻辑的潜在影响)、测试要点(testPoints数组)、contextInfluence（项目上下文如何影响本文件结论）。\n\n");
            p.append(FILE_HEADER).append(key).append(" （Java 源码）\n");
            p.append("若改动涉及对外可见 API（public/protected 方法/字段/类签名）的移除或签名变更，")
             .append("请在 impact 中给出迁移建议（旧调用 → 建议的新调用/写法；无直接替代时说明过渡方案）。\n\n");
        } else if (fc == FileClass.CONFIG) {
            p.append("请对以下配置文件（XML/Properties/YAML/JSON 等）改动做逐文件深读，返回 JSON：");
            p.append("改动意图(intent)、风险等级(risk: LOW/MEDIUM/HIGH)、影响范围(impact，含对配置项语义/模块行为/启动加载的潜在影响)、测试要点(testPoints数组)、contextInfluence（项目上下文如何影响本文件结论）。\n\n");
            p.append(FILE_HEADER).append(key).append(" （配置文件）\n");
        } else {
            p.append("请对以下Java类改动做逐文件深读，返回 JSON：");
            p.append("改动意图(intent)、风险等级(risk: LOW/MEDIUM/HIGH)、影响范围(impact)、测试要点(testPoints数组)、contextInfluence（项目上下文如何影响本文件结论）。\n");
            // FR-026 迁移建议：改动若触及 public/protected 对外签名，调用方需要「怎么改」而不只是「改了什么」——
            // 让 impact 字段承载旧调用→新调用片段，与阶段A 的 API 兼容性分级指令同口径
            p.append("若改动涉及对外可见 API（public/protected 方法/字段/类签名）的移除或签名变更，")
             .append("请在 impact 中给出迁移建议（旧调用 → 建议的新调用/写法；无直接替代时说明过渡方案）。\n");
            p.append("\n");
            p.append(FILE_HEADER).append(key).append("\n");
        }
        p.append("> 以下为仅含变更行的差异摘要：每块标注变更类型（修改/新增/删除）与行号区间（老 Lx-y → 新 La-b），"
                + "请在分析中引用具体行号定位变更，便于用户核对。\n\n");
        p.append("```diff\n").append(compactDiffForPrompt(unit)).append("\n```\n");
        return sanitize(p.toString(), cfg);
    }

    /**
     * 阶段B 单文件 diff 内容：仅列变更行（带行号与新增/删除/修改类型标注），不呈现完整文件，
     * 避免篇幅过大。由 {@link com.bempdiff.diff.DiffDigest} 解析 unified diff 生成紧凑摘要；
     * 摘要本身若仍超长（块内/块数护栏已使其远小于原文件），再做最终字符截断兜底。
     */
    private static String compactDiffForPrompt(DecompiledUnit unit) {
        String diff = (unit == null || unit.getDiffText() == null) ? "" : unit.getDiffText();
        String compact = com.bempdiff.diff.DiffDigest.render(diff);
        if (compact.length() <= STAGE_B_DIFF_CHAR_CAP) return compact;
        return compact.substring(0, STAGE_B_DIFF_CHAR_CAP)
                + "\n... (差异摘要过长已截断：共 " + compact.length() + " 字符，建议人工查看完整比对)\n";
    }

    /** 文本文件类型中文标签（用于阶段A 摘要标注与阶段B 措辞）。 */
    private static String frontendLabel(FileClass fc) {
        switch (fc) {
            case JS: return "JavaScript";
            case HTML: return "HTML 模板";
            case CSS: return "CSS 样式";
            case JSP: return "JSP 页面";
            case JAVA: return "Java 源码";
            case CONFIG: return "配置文件";
            default: return "文本资源";
        }
    }

    /** 由文件扩展名推断文本类型（用于阶段A 摘要标注；class 返回 null）。 */
    private static FileClass fileClassOf(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".js")) return FileClass.JS;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return FileClass.HTML;
        if (lower.endsWith(".css")) return FileClass.CSS;
        if (key.endsWith(".jsp") || key.endsWith(".jspx")
                || key.endsWith(".tag") || key.endsWith(".tagx")) return FileClass.JSP;
        if (lower.endsWith(".java")) return FileClass.JAVA;
        // .mf 与 PackageParser.classify 保持一致，让 AI 报告把 MANIFEST.MF 看作「配置文件」而非 nullptr
        if (lower.endsWith(".mf") || key.endsWith(".xml") || key.endsWith(".properties") || key.endsWith(".yml")
                || key.endsWith(".yaml") || key.endsWith(".json") || key.endsWith(".conf")
                || key.endsWith(".cfg") || key.endsWith(".tld") || key.endsWith(".xhtml")
                || key.endsWith(".wsdl") || key.endsWith(".xsl") || key.endsWith(".xslt")
                || key.endsWith(".dtd") || key.endsWith(".vm") || key.endsWith(".ftl")
                || key.endsWith(".ini") || key.endsWith(".toml") || key.endsWith(".txt")
                || key.endsWith(".csv")) return FileClass.CONFIG;
        // Office 文档（OpenXML zip）：与 PackageParser.classify 保持一致，AI 摘要/深读可标注「Office 文档」
        if (lower.endsWith(".docx") || lower.endsWith(".docm") || lower.endsWith(".dotx")
                || lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || lower.endsWith(".xltx")
                || lower.endsWith(".pptx") || lower.endsWith(".pptm")
                || lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt"))
            return FileClass.OFFICE;
        return null;
    }

    /** 脱敏：公网模型(o/a/z/q/custom 非本地)时，擦除疑似敏感字面量。 */
    public static String sanitize(String text, AiConfig cfg) {
        boolean local = "ollama".equalsIgnoreCase(cfg.getProvider())
                || cfg.getBaseUrl().contains("localhost") || cfg.getBaseUrl().contains("127.0.0.1");
        if (local) return text;  // 本地/私有化模型：代码不出机，无需脱敏
        // 擦除疑似身份证/密钥/手机号等（演示正则；量产版按业务规则细化）
        // 注意：用显式数字边界 (?<!\d)/(?!\d) 替代 \b，因为 Java 的 \b 在中文等
        // 非 ASCII 字符与数字相邻时不产生词边界，会导致「身份证110...」之类场景漏脱敏。
        return text
                .replaceAll("(?<!\\d)\\d{17}[\\dXx](?!\\d)", "***ID***")          // 18位身份证
                .replaceAll("(?<!\\w)(?:AKIA|AK|SK|KEY|SECRET|TOKEN|PASSWORD|PWD)[-.\\w=:]*[A-Za-z0-9+/=]{6,}", "***SECRET***")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "***PHONE***");         // 手机号
    }

    /** 追加聚焦指令：focus 非空时引导模型在该维度深入分析与结论（类别化报告的核心）。 */
    private static void appendFocus(StringBuilder p, String focus) {
        if (focus == null || focus.trim().isEmpty()) return;
        p.append("\n## 本次分析聚焦\n").append(focus.trim())
         .append("\n请在本聚焦维度上给出更详尽的分析与可执行的结论。\n");
    }

    /** 阶段A prompt（聚焦类别增强）：在基础 prompt 后追加聚焦指令，引导模型在指定维度深入。 */
    public static String buildStageA(DiffResult diff, Map<String, DecompiledUnit> decompiled,
                                     AiConfig cfg, ProjectContext ctx, String focus) {
        // 历史兼容重载：无显式类别时按聚焦文本反推（仅供非分析项组装路径使用；生产主路径走 6 参版本）
        return buildStageA(diff, decompiled, cfg, ctx, focus, categoryOfFocus(focus));
    }

    /** 阶段A prompt（权威类别增强）：与 5 参变体等价，但「分类结论 schema」以显式 {@code category} 为唯一
     *  事实源（由调用方 normalizeCategory 得出），避免从聚焦文本反推导致 prompt 注入与渲染类别错位的隐患。 */
    public static String buildStageA(DiffResult diff, Map<String, DecompiledUnit> decompiled,
                                     AiConfig cfg, ProjectContext ctx, String focus, String category) {
        StringBuilder p = new StringBuilder(buildStageA(diff, decompiled, cfg, ctx));
        appendFocus(p, focus);
        appendCategorySchema(p, category);
        return sanitize(p.toString(), cfg);
    }

    /** 阶段A prompt（权威类别增强 + 顶层统计口径）：头部聚合数用 topStats（与折叠树一致）。 */
    public static String buildStageA(DiffResult diff, Map<String, DecompiledUnit> decompiled,
                                     AiConfig cfg, ProjectContext ctx, String focus, String category, DiffStats topStats) {
        StringBuilder p = new StringBuilder(buildStageABody(diff, decompiled, cfg, ctx, topStats));
        appendFocus(p, focus);
        appendCategorySchema(p, category);
        return sanitize(p.toString(), cfg);
    }

    /**
     * 由聚焦指令解析类别键（breaking/impact/testpoints/risk，其余为空串）。
     * 与 MockAiAnalyzer.FocusKind.of 的判定口径保持一致，避免「prompt 注入」与「兜底差异化」错位。
     */
    public static String categoryOfFocus(String focus) {
        if (focus == null || focus.trim().isEmpty()) return "";
        if (focus.contains("破坏性") || focus.contains("兼容性") || focus.contains("接口契约")) return "breaking";
        if (focus.contains("影响范围") || focus.contains("上下游") || focus.contains("依赖")) return "impact";
        if (focus.contains("测试") || focus.contains("回归")) return "testpoints";
        if (focus.contains("风险") || focus.contains("降级") || focus.contains("回滚")) return "risk";
        return "";
    }

    /** 分类结论 schema 注入：按权威类别在「conclusion」对象内要求结构化字段；
     *  空/未知/custom 不注入（渲染端自动回落为整体全面，避免专项结构错配）。 */
    private static void appendCategorySchema(StringBuilder p, String category) {
        if (category == null || category.isEmpty() || "custom".equals(category)) {
            return;
        }
        p.append("\n## 分类结论输出（请按本类别返回差异化 JSON 结构）\n");
        p.append("在返回的 JSON 中额外包含字段 `conclusion`（对象），并按以下结构组织：\n");
        switch (category) {
            case "breaking":
                p.append("- `breakingChanges`：数组，元素含 `file`(文件)、`changeType`(删除/签名变更/接口契约破坏等)、"
                        + "`change`(具体变更点)、`compatImpact`(兼容性影响)、`severity`(LOW/MEDIUM/HIGH)、"
                        + "`migrationSuggestion`(迁移改造建议)\n");
                p.append("- `compatibilityVerdict`：整体兼容性结论（不兼容点数量与严重度分级、需人工核对的接口清单）\n");
                break;
            case "impact":
                p.append("- `affectedModules`：受影响模块/服务\n- `affectedApis`：受影响的对外接口/页面/接口契约\n"
                        + "- `internalCallers`：受影响内部调用方与依赖链路\n- `diffusion`：影响扩散路径与数据流\n");
                break;
            case "testpoints":
                p.append("- `testPoints`：数组，元素含 `item`(测试项)、`file`(涉及文件)、`scenario`(测试场景)、"
                        + "`caseIdea`(用例思路)、`verifyFocus`(验证重点)\n");
                break;
            case "risk":
                p.append("- `riskRationale`：整体风险等级判定依据\n- `rollbackPlan`：回滚预案\n"
                        + "- `degradationPlan`：降级预案\n");
                break;
            default:
                return;
        }
        p.append("- `suggestions`：具体、可实施的优化改造建议（Markdown 多行字符串，3~8 条为佳）\n");
        p.append("`conclusion` 内容请聚焦本分析维度、仅保留与维度直接相关的内容，"
                + "并紧密结合【项目级上下文】中的模块/依赖/入口/配置，使结论贴合实际项目环境。\n");
    }

    /** 阶段B 单文件 prompt（聚焦类别增强）。 */
    public static String buildStageB(String key, DecompiledUnit unit, FileClass fc, AiConfig cfg, ProjectContext ctx, String focus) {
        StringBuilder p = new StringBuilder(buildStageB(key, unit, fc, cfg, ctx));
        appendFocus(p, focus);
        return sanitize(p.toString(), cfg);
    }

    private static DiffStatus stOf(DiffResult diff, String k) {
        for (DiffStatus st : DiffStatus.values()) {
            if (diff.get(st).contains(k)) return st;
        }
        return DiffStatus.MODIFIED;
    }

    private static String statusName(DiffStatus st) {
        switch (st) {
            case ADDED: return "新增";
            case DELETED: return "删除";
            default: return "修改";
        }
    }
}

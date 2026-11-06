package com.bempdiff;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.decompile.Decompiler;
import com.bempdiff.diff.DiffEngine;
import com.bempdiff.diff.FrontendTextDiff;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.diff.FolderDiff;
import com.bempdiff.diff.FolderEntryFormatter;
import com.bempdiff.model.*;
import com.bempdiff.parse.PackageParser;
import com.bempdiff.parse.FolderParser;
import com.bempdiff.report.MarkdownReport;
import com.bempdiff.report.FolderReport;
import com.bempdiff.export.AssetExporter;
import com.bempdiff.ai.AiAnalyzer;
import com.bempdiff.config.AiConfig;
import com.bempdiff.ai.StageASummary;
import com.bempdiff.ai.FileAnalysis;
import com.bempdiff.ai.MockAiAnalyzer;
import com.bempdiff.ai.HttpAiAnalyzer;
import com.bempdiff.ai.context.ProjectContext;
import com.bempdiff.ai.context.ProjectContextService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Java 移植端口验证驱动（不依赖 JavaFX）。
 * 用法：
 *   java -cp out com.bempdiff.Main inspect <pkg>
 *   java -cp out com.bempdiff.Main compare <old> <new> [--expand-all]
 *   java -cp out com.bempdiff.Main decompile <old> <new> [--expand-all] [--top-k N] [--cfr <cfr.jar>]
 */
public final class Main { // NOSONAR(S6539) - CLI 聚合入口，依赖面广但职责单一

    private static final String STATS_PREFIX = "stats: ";
    private static final String ARG_REPORT = "--report";
    private static final String ARG_REPLAY = "--replay";
    private static final String ARG_PROJECT = "--project";
    private static final String ARG_APIKEY = "--apikey";
    private static final String ARG_PROVIDER = "--provider";
    private static final String ARG_BASEURL = "--baseurl";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(Main.class.getName());

    private static int layerCount(Map<String, LogicalEntry> entries, Layer layer) {
        int c = 0;
        for (LogicalEntry e : entries.values()) if (e.getLayer() == layer) c++;
        return c;
    }

    private static void inspect(Path p) throws IOException {
        PackageParser parser = new PackageParser();
        PackageSnapshot snap = parser.parse(p, new ParseConfig(), false);
        System.out.println("== INSPECT =="); // NOSONAR - CLI 工具的标准输出
        System.out.println("file   : " + p.getFileName()); // NOSONAR
        System.out.println("type   : " + snap.getType()); // NOSONAR
        System.out.println("version: " + snap.getVersion()); // NOSONAR
        System.out.println("total  : " + snap.getTotal()); // NOSONAR
        System.out.println("L0     : " + layerCount(snap.getEntries(), Layer.L0)); // NOSONAR
        System.out.println("L1     : " + layerCount(snap.getEntries(), Layer.L1)); // NOSONAR
        System.out.println("L2     : " + layerCount(snap.getEntries(), Layer.L2)); // NOSONAR
    }

    private static void compare(Path oldP, Path newP, boolean expandAll) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获，用于统一错误处理入口
        // 智能识别：同名不同版本的压缩包自动按版本升序（旧→新），无需手动分辨新旧。
        if (com.bempdiff.parse.PackageVersion.sameBaseDifferentVersion(
                oldP.getFileName().toString(), newP.getFileName().toString())) {
            String[] ordered = com.bempdiff.parse.PackageVersion.orderOldNew(
                    oldP.getFileName().toString(), newP.getFileName().toString());
            if (!ordered[0].equals(oldP.getFileName().toString())) {
                Path t = oldP;
                oldP = newP;
                newP = t;
                System.out.println("[智能识别] 检测到同名不同版本，已自动按版本排序：旧=" // NOSONAR
                        + oldP.getFileName() + " 新=" + newP.getFileName());
            }
        }
        PackageParser parser = new PackageParser();
        ParseConfig cfg = new ParseConfig();
        PackageSnapshot oldSnap = parser.parse(oldP, cfg, expandAll);
        PackageSnapshot newSnap = parser.parse(newP, cfg, expandAll);
        DiffEngine engine = new DiffEngine();
        DiffResult r = engine.compute(oldSnap, newSnap);
        DiffStats s = engine.stats(r);

        System.out.println("== COMPARE =="); // NOSONAR
        System.out.println("old: " + oldP.getFileName() + "  version=" + oldSnap.getVersion()); // NOSONAR
        System.out.println("new: " + newP.getFileName() + "  version=" + newSnap.getVersion()); // NOSONAR
        System.out.println(STATS_PREFIX + s); // NOSONAR
        printTree(r, oldSnap, newSnap);
    }

    /** FR：文件夹比较。与 compare 共享差异算法与树打印，仅数据源换成 FolderParser。 */
    private static void compareFolders(Path oldDir, Path newDir) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获
        FolderParser parser = new FolderParser();
        ParseConfig cfg = new ParseConfig();
        PackageSnapshot oldSnap = parser.parse(oldDir, cfg);
        PackageSnapshot newSnap = parser.parse(newDir, cfg);
        DiffEngine engine = new DiffEngine();
        DiffResult r = engine.compute(oldSnap, newSnap);
        DiffStats s = engine.stats(r);

        System.out.println("== COMPARE FOLDERS =="); // NOSONAR
        System.out.println("old dir: " + oldDir); // NOSONAR
        System.out.println("new dir: " + newDir); // NOSONAR
        System.out.println(STATS_PREFIX + s); // NOSONAR
        printTree(r, oldSnap, newSnap);
    }

    /** FR：文件夹对比（增强版，独立引擎）。递归比对两目录，区分仅左/仅右/两侧不同(内容或属性)/相同/类型冲突，
     *  输出清晰汇总 + 展开式差异树（含属性变更与文本行级 diff）+ 可选 Markdown 报告。 */
    private static void folderDiff(Path leftDir, Path rightDir, int topK, FolderDiff.Options opts, Path reportMd) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获
        FolderDiff.FolderDiffResult r = FolderDiff.compare(leftDir, rightDir, opts);
        System.out.println("== FOLDER DIFF =="); // NOSONAR
        System.out.println("left : " + leftDir); // NOSONAR
        System.out.println("right: " + rightDir); // NOSONAR
        System.out.println("--- 汇总 ---"); // NOSONAR
        System.out.println("仅左侧存在 : " + r.summary.getLeftOnly()); // NOSONAR
        System.out.println("仅右侧存在 : " + r.summary.getRightOnly()); // NOSONAR
        System.out.println("两侧不同   : " + r.summary.getModified() + " (内容不同=" + r.summary.getContentChanged() // NOSONAR
                + ", 仅属性不同=" + r.summary.getAttrOnlyChanged() + ")"); // NOSONAR
        System.out.println("完全相同   : " + r.summary.getSame()); // NOSONAR
        System.out.println("类型冲突   : " + r.summary.getTypeMismatch()); // NOSONAR
        System.out.println("扫描文件/目录: " + r.summary.getScannedFiles() + "/" + r.summary.getScannedDirs() // NOSONAR
                + "  读取错误: " + r.summary.getErrors()); // NOSONAR
        System.out.println("--- 差异树 (展开) ---"); // NOSONAR
        printFolderTree(r.roots, "");
        if (reportMd != null) {
            FolderReport.writeToFile(r, reportMd);
            System.out.println("report: " + reportMd); // NOSONAR
        }
    }

    /** 展开式差异树打印（缩进体现层级，标注状态与属性变更；文本文件内容不同则附行级 diff）。 */
    private static void printFolderTree(List<FolderDiff.FolderEntry> nodes, String indent) {
        for (FolderDiff.FolderEntry e : nodes) {
            String marker = FolderEntryFormatter.marker(e);
            String name = FolderEntryFormatter.name(e);
            StringBuilder sb = new StringBuilder();
            sb.append(indent).append(marker).append(' ').append(name);
            if (e.type == FolderDiff.EntryType.DIR) sb.append('/');
            if (e.status == FolderDiff.FolderDiffStatus.MODIFIED) sb.append(' ').append(FolderEntryFormatter.attrSummary(e));
            else if (FolderEntryFormatter.subtreeDiff(e)) sb.append(" (子树含差异)");
            System.out.println(sb); // NOSONAR
            List<String> lines = FolderEntryFormatter.lineDiffLines(e);
            if (lines != null) {
                for (String line : lines) {
                    System.out.println(indent + "    " + line); // NOSONAR
                }
            }
            if (e.children != null && !e.children.isEmpty()) {
                printFolderTree(e.children, indent + "  ");
            }
        }
    }

    /** 统一的差异树打印（按 status 分组，缩进体现层级，标注 layer）。 */
    private static void printTree(DiffResult r, PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        System.out.println("--- tree (status / layer) ---"); // NOSONAR
        DiffStatus[] order = {DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.DELETED, DiffStatus.UNCHANGED};
        for (DiffStatus st : order) {
            for (String k : r.get(st)) {
                LogicalEntry e = oldSnap.getEntries().get(k);
                if (e == null) e = newSnap.getEntries().get(k);
                Layer lay = e == null ? null : e.getLayer();
                String layName = lay == null ? "?" : lay.name();
                String indent = repeat("  ", k.split("/").length - 1);
                System.out.println(indent + st + " [" + layName + "] " + k); // NOSONAR
            }
        }
    }

    /** T07/T08：对 L1 修改/新增/删除类反编译并双栏源码 diff（真实包验证） */
    private static void decompile(Path oldP, Path newP, boolean expandAll, int topK, Path cfrJar) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获，用于统一错误处理入口
        PackageParser parser = new PackageParser();
        ParseConfig cfg = new ParseConfig();
        PackageSnapshot oldSnap = parser.parse(oldP, cfg, expandAll);
        PackageSnapshot newSnap = parser.parse(newP, cfg, expandAll);
        DiffEngine engine = new DiffEngine();
        DiffResult r = engine.compute(oldSnap, newSnap);

        String javaBin = findJava();
        Decompiler dec = new Decompiler(cfrJar, javaBin);

        // 候选：L1 的 修改/新增/删除 class（前 topK）
        List<String> cands = DiffEngine.collectL1ClassCandidates(r, oldSnap, newSnap);
        int skipped = Math.max(0, cands.size() - topK);
        if (cands.size() > topK) cands = cands.subList(0, topK);

        System.out.println("== DECOMPILE =="); // NOSONAR
        System.out.println("old: " + oldP.getFileName() + "  new: " + newP.getFileName()); // NOSONAR
        System.out.println("L1 class 候选=" + (cands.size() + skipped) + " 反编译=" + cands.size() + " 跳过=" + skipped + " (topK=" + topK + ")"); // NOSONAR
        int okCnt = 0;
        for (String k : cands) {
            LogicalEntry oe = oldSnap.getEntries().get(k);
            LogicalEntry ne = newSnap.getEntries().get(k);
            DecompiledUnit u = dec.decompile(oldSnap, newSnap, oe, ne, k);
            System.out.println("----------------------------------------"); // NOSONAR
            System.out.println("KEY   : " + k); // NOSONAR
            System.out.println("ENGINE: " + u.getEngine() + "  OK=" + u.isOk()); // NOSONAR
            if (!u.isOk()) {
                System.out.println("ERROR : " + u.getError()); // NOSONAR
                continue;
            }
            okCnt++;
            if (u.getDiffText() != null && u.getDiffText().length() > 2000) {
                System.out.println("DIFF  : (截断展示前 2000 字符)\n" + u.getDiffText().substring(0, 2000)); // NOSONAR
            } else {
                System.out.println("DIFF  :\n" + u.getDiffText()); // NOSONAR
            }
        }
        // 文本类文件（配置文件/JSP/JS/HTML/CSS）：压缩前端资源美化后 diff，配置文件与 JSP 换行归一后 diff
        Map<String, DecompiledUnit> fe = buildTextMap(r, oldSnap, newSnap, topK);
        int feOk = 0;
        for (Map.Entry<String, DecompiledUnit> e : fe.entrySet()) {
            DecompiledUnit u = e.getValue();
            System.out.println("----------------------------------------"); // NOSONAR
            System.out.println("KEY   : " + e.getKey() + " (文本 " + fileClassOfKey(e.getKey(), oldSnap, newSnap) + ")"); // NOSONAR
            System.out.println("ENGINE: " + u.getEngine() + "  OK=" + u.isOk()); // NOSONAR
            if (u.isOk()) {
                feOk++;
                if (u.getDiffText() != null && u.getDiffText().length() > 2000) {
                    System.out.println("DIFF  : (截断展示前 2000 字符)\n" + u.getDiffText().substring(0, 2000)); // NOSONAR
                } else {
                    System.out.println("DIFF  :\n" + u.getDiffText()); // NOSONAR
                }
            } else {
                System.out.println("ERROR : " + u.getError()); // NOSONAR
            }
        }
        System.out.println("----------------------------------------"); // NOSONAR
        System.out.println("反编译成功=" + okCnt + " / " + cands.size() // NOSONAR(S106) - CLI 控制台输出
                + "  文本类文件比对=" + feOk + " / " + fe.size()); // NOSONAR
    }

    private static String findJava() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) {
            Path cand = Paths.get(jh, "bin", "java.exe");
            if (cand.toFile().isFile()) return cand.toString();
            Path cand2 = Paths.get(jh, "bin", "java");
            if (cand2.toFile().isFile()) return cand2.toString();
        }
        return "java";
    }

    /** CLI 子命令（report/diffJars/export/ai）共享的前置产物：双包解析 + 差异计算 + 统计。 */
    private record PrepResult(PackageSnapshot oldSnap, PackageSnapshot newSnap, DiffResult r, DiffStats s) {}

    /** 双包解析 + 差异计算（各 CLI 子命令共享，避免重复样板）。 */
    private static PrepResult prepCompare(Path oldP, Path newP, boolean expandAll) throws IOException {
        PackageParser parser = new PackageParser();
        ParseConfig cfg = new ParseConfig();
        PackageSnapshot oldSnap = parser.parse(oldP, cfg, expandAll);
        PackageSnapshot newSnap = parser.parse(newP, cfg, expandAll);
        DiffEngine engine = new DiffEngine();
        DiffResult r = engine.compute(oldSnap, newSnap);
        return new PrepResult(oldSnap, newSnap, r, engine.stats(r));
    }

    /** 对差异 Top-K class 候选反编译（各 CLI 子命令共享），返回保持候选顺序的 map。 */
    private static Map<String, DecompiledUnit> decompileTop(PrepResult prep, Decompiler dec, int topK) {
        Map<String, DecompiledUnit> decompiled = new LinkedHashMap<>();
        List<String> cands = DiffEngine.collectL1ClassCandidates(prep.r(), prep.oldSnap(), prep.newSnap());
        if (cands.size() > topK) cands = cands.subList(0, topK);
        for (String k : cands) {
            decompiled.put(k, dec.decompile(prep.oldSnap(), prep.newSnap(),
                    prep.oldSnap().getEntries().get(k), prep.newSnap().getEntries().get(k), k));
        }
        return decompiled;
    }

    /** 解析可选 --project <dir> 参数加载项目级上下文；失败降级为 null（不阻塞分析主流程）。 */
    private static ProjectContext loadProjectContext(List<String> a) {
        if (!a.contains(ARG_PROJECT)) return null;
        try {
            Path projDir = Paths.get(a.get(a.indexOf(ARG_PROJECT) + 1));
            ProjectContext ctx = ProjectContextService.renderContextView(ProjectContextService.resolve(projDir.toString()));
            LOG.log(java.util.logging.Level.INFO, "[AI] 项目级上下文已加载：{0}（构建系统={1}, 模块数={2}）",
                    new Object[]{projDir, ctx.getBuildSystem(), ctx.getModules().size()});
            return ctx;
        } catch (RuntimeException ex) {
            LOG.log(java.util.logging.Level.WARNING, "[AI] 项目级上下文扫描失败，已忽略：{0}", ex.getMessage());
            return null;
        }
    }

    /** T12/FR7：全链路 + Markdown 报告导出（真实包验证）。--ai 时加载持久化 AI 配置，运行两阶段分析并原生写回「七、AI 智能分析」章节。 */
    /** report/ci 共用产物：stats 与 DiffResult 供 ci 子命令判定 hasDiff 并生成 JSON 摘要；apiCompat/impact 供 ci JSON 分级段；aiUsageLine 供 ai.usage。 */
    private record ReportOut(DiffStats s, DiffResult r, StageASummary summary,
                             com.bempdiff.diff.ApiCompat.Result apiCompat,
                             com.bempdiff.diff.ImpactAnalyzer.Result impact,
                             String aiUsageLine) {}

    private static ReportOut report(Path oldP, Path newP, boolean expandAll, int topK, Path cfrJar, Path outMd, List<String> a) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获，用于统一错误处理入口
        PrepResult prep = prepCompare(oldP, newP, expandAll);
        PackageSnapshot oldSnap = prep.oldSnap();
        PackageSnapshot newSnap = prep.newSnap();
        DiffResult r = prep.r();
        DiffStats s = prep.s();

        Decompiler dec = new Decompiler(cfrJar, findJava());
        Map<String, DecompiledUnit> decompiled = decompileTop(prep, dec, topK);
        Map<String, DecompiledUnit> text = buildTextMap(r, oldSnap, newSnap, topK);
        // Req 6：差异依赖 JAR 内部源码对比章节（自动识别差异 lib jar → 反编译内部 class → 源码级 diff）
        com.bempdiff.diff.LibJarDiff.Result libJar = com.bempdiff.diff.LibJarDiff.analyze(
                oldSnap, newSnap, r, dec, topK);

        MarkdownReport rep = new MarkdownReport(topK);
        // T01810 FR-006 展示层：AST 折叠计数与降级计数进报告统计段
        rep.setFormattedOnlyCount(r.formattedOnlyCount());
        rep.setAstDegradedCount(com.bempdiff.diff.AstNormalizer.degradedCount());

        // FR-007：API 兼容性静态分级（复用 FR-006 AST 基建）——decompiled 此刻仅含 class 候选
        //（text 尚未并入），天然是 Java 源码集；结果进报告章节 + AI 上下文 + ci JSON。
        com.bempdiff.diff.ApiCompat.Result apiCompat = com.bempdiff.diff.ApiCompat.analyze(decompiled);
        rep.setApiCompatSection(apiCompat.toMarkdownSection());

        // FR-012：变更影响面（反编译集内调用关系 × 签名分级 → 风险等级 + 证据链）。
        // 删除类取 DiffResult.DELETED 中 .java 条目（源码包场景的反编译集内整类删除）。
        List<String> deletedJavaKeys = new ArrayList<>();
        for (String dk : r.get(DiffStatus.DELETED)) {
            if (dk.endsWith(".java")) deletedJavaKeys.add(dk);
        }
        com.bempdiff.diff.ImpactAnalyzer.Result impact =
                com.bempdiff.diff.ImpactAnalyzer.analyze(decompiled, apiCompat, deletedJavaKeys);
        rep.setImpactSection(impact.toMarkdownSection());

        // --ai：两阶段 AI 分析并写回「七、AI 智能分析」章节（FR-CX-01），支持项目级上下文增强。
        // 有 API Key 走真实 Http 调用；无 Key 时降级为离线 Mock 回放（仍能产出 AI 章节 + 项目上下文影响说明）。
        if (a.contains("--ai")) {
            AiConfig aiCfg = loadAiConfig(a);
            Path replayDir = Paths.get(System.getProperty("user.home"), ".bempdiff", "ai_replay");
            if (a.contains(ARG_REPLAY)) replayDir = Paths.get(a.get(a.indexOf(ARG_REPLAY) + 1));
            AiAnalyzer analyzer;
            if (aiCfg.getApiKey() != null && !aiCfg.getApiKey().isEmpty()) {
                analyzer = new HttpAiAnalyzer(aiCfg);
                LOG.log(java.util.logging.Level.INFO, "[AI] 使用 HttpAiAnalyzer（真实调用，provider={0}）", aiCfg.getProvider());
            } else {
                analyzer = new MockAiAnalyzer(replayDir);
                LOG.warning("[AI] 未配置 API Key，使用 MockAiAnalyzer 离线回放生成 AI 章节（如需真实分析请在 ⚙设置 填 Key 或用 --apikey；--replay 可指定回放目录）。");
            }
            // 项目级上下文增强（可选 --project <dir>）：离线扫描工程目录，作为 AI 分析依据
            ProjectContext ctx = loadProjectContext(a);
            boolean conn = analyzer.testConnection(aiCfg);
            LOG.log(java.util.logging.Level.INFO, "[AI] 连接测试={0}", conn);
            Map<String, DecompiledUnit> aiMap = new LinkedHashMap<>(decompiled);
            aiMap.putAll(text);
            StageASummary summary;
            try {
                // FR-007/FR-012 验收②：API 分级 + 影响面随 aiMap 注入 AI 上下文（接口 default 合成条目，实现类零改动）
                summary = analyzer.stageA(r, aiMap, aiCfg, ctx, apiCompat, impact);
            } catch (RuntimeException ex) {
                LOG.log(java.util.logging.Level.WARNING, "[AI] 阶段A 调用失败，已降级：{0}", ex.getMessage());
                summary = new StageASummary();
                summary.setOverallRisk("UNKNOWN");
                summary.setImpactScope("AI 阶段A 不可用（连接失败/超时/鉴权错误），仅给出基础差异结论。错误：" + ex.getMessage());
                summary.setTestThemes(java.util.Arrays.asList("人工核对全部差异文件", "回归核心业务流程", "校验对外接口兼容性"));
                summary.setFileRisks(new ArrayList<>());
            }
            List<FileAnalysis> b = new ArrayList<>();
            try {
                List<AiAnalyzer.DecompileReq> bCands = new ArrayList<>();
                for (Map.Entry<String, DecompiledUnit> e : aiMap.entrySet()) {
                    bCands.add(new AiAnalyzer.DecompileReq(e.getKey(), e.getValue(), fileClassOfKey(e.getKey(), oldSnap, newSnap)));
                }
                if (bCands.size() > aiCfg.getStageBTopK()) bCands = bCands.subList(0, aiCfg.getStageBTopK());
                b = analyzer.stageB(bCands, aiCfg, ctx);
            } catch (RuntimeException ex) {
                LOG.log(java.util.logging.Level.WARNING, "[AI] 阶段B 调用失败，已降级（仅保留阶段A）：{0}", ex.getMessage());
            }
            // FR-013：实际消耗（输入/输出分段）写进审计摘要与 ReportOut（Mock/离线链路无 usage → 空行不渲染）
            String usageLine = "";
            if (analyzer instanceof HttpAiAnalyzer http) {
                usageLine = "输入 " + http.getUsagePromptTokens() + " / 输出 "
                        + http.getUsageCompletionTokens() + " tokens（" + http.getUsageCalls() + " 次调用）";
                rep.setAiUsageLine(usageLine);
            }
            rep.writeToFile(oldSnap, newSnap, r, s, decompiled, text, summary, b, ctx, libJar, outMd);
            System.out.println("== REPORT (AI) =="); // NOSONAR
            System.out.println("项目级上下文: " + (ctx != null && !ctx.isEmpty() ? ("已启用（构建系统=" + ctx.getBuildSystem() + ", 模块数=" + ctx.getModules().size() + "）") : "未启用")); // NOSONAR
            System.out.println("out: " + outMd); // NOSONAR
            System.out.println("AI 整体风险=" + summary.getOverallRisk() + "  阶段B 深读=" + b.size() + " 文件"); // NOSONAR
            if (!usageLine.isEmpty()) System.out.println("AI 实际消耗: " + usageLine); // NOSONAR
            System.out.println(STATS_PREFIX + s + "  decompiled=" + decompiled.size() + "  text=" + text.size() // NOSONAR(S106) - CLI 控制台输出
                    + "  libJar=" + (libJar.isEmpty() ? "无差异JAR" : libJar.totalJars + "个")); // NOSONAR
            return new ReportOut(s, r, summary, apiCompat, impact, usageLine);
        }

        rep.writeToFile(oldSnap, newSnap, r, s, decompiled, text, libJar, outMd);
        System.out.println("== REPORT =="); // NOSONAR
        System.out.println("out: " + outMd); // NOSONAR
        System.out.println(STATS_PREFIX + s + "  decompiled=" + decompiled.size() // NOSONAR(S106) - CLI 控制台输出
                + "  text=" + text.size()
                + "  libJar=" + (libJar.isEmpty() ? "无差异JAR" : libJar.totalJars + "个")); // NOSONAR
        return new ReportOut(s, r, null, apiCompat, impact, "");
    }

    /** Req 6：差异依赖 JAR 内部源码对比。自动识别差异 lib jar → 提取内部 class → 反编译 → 逐对源码 diff，
     *  输出聚焦报告（含「五、差异依赖 JAR 内部源码对比」章节）+ 控制台汇总。 */
    private static void diffJars(Path oldP, Path newP, boolean expandAll, int topK, Path cfrJar, Path outMd) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获，用于统一错误处理入口
        PrepResult prep = prepCompare(oldP, newP, expandAll);
        PackageSnapshot oldSnap = prep.oldSnap();
        PackageSnapshot newSnap = prep.newSnap();
        DiffResult r = prep.r();
        DiffStats s = prep.s();

        Decompiler dec = new Decompiler(cfrJar, findJava());
        com.bempdiff.diff.LibJarDiff.Result libJar = com.bempdiff.diff.LibJarDiff.analyze(
                oldSnap, newSnap, r, dec, topK);

        System.out.println("== DIFF-JARS =="); // NOSONAR
        System.out.println("old: " + oldP.getFileName()); // NOSONAR
        System.out.println("new: " + newP.getFileName()); // NOSONAR
        if (libJar.isEmpty()) {
            System.out.println("差异依赖 JAR: 无（WEB-INF/lib 下无 ADDED/MODIFIED/DELETED 的 JAR）"); // NOSONAR
        } else {
            long jarAdded = 0;
            long jarModified = 0;
            long jarDeleted = 0;
            for (com.bempdiff.diff.LibJarDiff.DiffJarInfo jar : libJar.jars) {
                if (jar.jarStatus == DiffStatus.ADDED) jarAdded++;
                else if (jar.jarStatus == DiffStatus.MODIFIED) jarModified++;
                else if (jar.jarStatus == DiffStatus.DELETED) jarDeleted++;
            }
            System.out.println("差异依赖 JAR 数: " + libJar.totalJars // NOSONAR
                    + "（新增=" + jarAdded + " / 修改=" + jarModified + " / 删除=" + jarDeleted + "）"); // NOSONAR
            for (com.bempdiff.diff.LibJarDiff.DiffJarInfo jar : libJar.jars) {
                System.out.println("  - " + jar.jarKey + " [" + jar.jarStatus + "]" // NOSONAR
                        + "  class: +" + jar.added + " -" + jar.removed
                        + " ~" + jar.modified + " =" + jar.unchanged); // NOSONAR
            }
            System.out.println("内部 class 合计: 新增 " + libJar.totalAdded // NOSONAR
                    + " · 删除 " + libJar.totalRemoved + " · 修改 " + libJar.totalModified
                    + " · 未变 " + libJar.totalUnchanged); // NOSONAR
        }

        // 生成聚焦报告（含通用章节 + 差异 JAR 内部源码对比章节）
        Map<String, DecompiledUnit> decompiled = decompileTop(prep, dec, topK);
        Map<String, DecompiledUnit> text = buildTextMap(r, oldSnap, newSnap, topK);
        new MarkdownReport(topK)
                .setFormattedOnlyCount(r.formattedOnlyCount())
                .setAstDegradedCount(com.bempdiff.diff.AstNormalizer.degradedCount())
                .writeToFile(oldSnap, newSnap, r, s, decompiled, text, libJar, outMd);
        System.out.println("report: " + outMd); // NOSONAR
    }

    /** 从持久化 ui-config.properties 加载 AI 配置（与 UI 同路径）；CLI --apikey/--baseurl/--provider/--model/--aiconfig 可覆盖。 */
    private static AiConfig loadAiConfig(List<String> a) {
        AiConfig c = new AiConfig();
        Path cfgPath;
        if (a.contains("--aiconfig")) {
            cfgPath = Paths.get(a.get(a.indexOf("--aiconfig") + 1));
        } else {
            cfgPath = Paths.get(System.getProperty("user.home"), ".bempdiff", "ui-config.properties");
        }
        if (Files.exists(cfgPath)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(cfgPath)) {
                p.load(in);
                c.setProvider(p.getProperty("aiProvider", c.getProvider()));
                c.setBaseUrl(p.getProperty("aiBaseUrl", c.getBaseUrl()));
                c.setApiKey(p.getProperty("aiApiKey", ""));
                c.setModel(p.getProperty("aiModel", c.getModel()));
                c.setStageBTopK(Integer.parseInt(p.getProperty("stageBTopK", "15")));
                c.setCostGateWarnTokens(Double.parseDouble(p.getProperty("costGateWarnTokens", "8000")));
                c.setHttpProxy(p.getProperty("httpProxy", ""));
                c.setHttpsProxy(p.getProperty("httpsProxy", ""));
                c.setBlockPrivateEndpoints(Boolean.parseBoolean(p.getProperty("blockPrivateEndpoints", "false")));
                c.setEnabled(true);
            } catch (IOException | NumberFormatException ex) {
                LOG.log(java.util.logging.Level.WARNING, "[AI] 读取配置文件失败，使用 CLI/默认值：{0}", ex.getMessage());
            }
        }
        // CLI 覆盖（优先级最高）
        if (a.contains(ARG_APIKEY)) c.setApiKey(a.get(a.indexOf(ARG_APIKEY) + 1));
        if (a.contains(ARG_PROVIDER)) c.setProvider(a.get(a.indexOf(ARG_PROVIDER) + 1));
        if (a.contains(ARG_BASEURL)) c.setBaseUrl(a.get(a.indexOf(ARG_BASEURL) + 1));
        if (a.contains("--model")) c.setModel(a.get(a.indexOf("--model") + 1));
        if (c.getApiKey() != null && !c.getApiKey().isEmpty()) c.setEnabled(true);
        return c;
    }

    /** T13/FR6：全链路 + 差异资产导出（真实包验证） */
    private static void export(Path oldP, Path newP, boolean expandAll, int topK, Path cfrJar, Path outDir) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获，用于统一错误处理入口
        PrepResult prep = prepCompare(oldP, newP, expandAll);
        PackageSnapshot oldSnap = prep.oldSnap();
        PackageSnapshot newSnap = prep.newSnap();
        DiffResult r = prep.r();
        DiffStats s = prep.s();

        Decompiler dec = new Decompiler(cfrJar, findJava());
        Map<String, DecompiledUnit> decompiled = decompileTop(prep, dec, topK);
        // 文本类文件（配置文件/JSP/前端 JS/HTML/CSS）一并纳入源码导出 zip（按扩展名落盘）
        Map<String, DecompiledUnit> text = buildTextMap(r, oldSnap, newSnap, topK);
        decompiled.putAll(text);

        AssetExporter exporter = new AssetExporter();
        Path classesDir = exporter.exportDiffClasses(r, oldSnap, newSnap, outDir);
        Path jarsDir = exporter.exportDiffJars(r, oldSnap, newSnap, outDir);
        Path zip = exporter.exportDecompiledSources(decompiled, outDir, topK);

        System.out.println("== EXPORT =="); // NOSONAR
        System.out.println("outDir : " + outDir); // NOSONAR
        System.out.println("classes: " + classesDir + " (" + countFiles(classesDir) + " class 文件)"); // NOSONAR
        System.out.println("jars    : " + jarsDir + " (" + countFiles(jarsDir) + " jar 文件)"); // NOSONAR
        System.out.println("src zip : " + zip + " (" + decompiled.size() + " 源码，含 " + text.size() + " 文本类)"); // NOSONAR
        System.out.println(STATS_PREFIX + s); // NOSONAR
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    /** T09/T10/T11：全链路 + 两阶段 AI 分析（离线回放，不触网、无需 Key） */
    private static void ai(Path oldP, Path newP, boolean expandAll, int topK, Path cfrJar, Path replayDir, List<String> a) throws IOException { // NOSONAR - 顶层 main 方法通用异常捕获，用于统一错误处理入口
        PrepResult prep = prepCompare(oldP, newP, expandAll);
        PackageSnapshot oldSnap = prep.oldSnap();
        PackageSnapshot newSnap = prep.newSnap();
        DiffResult r = prep.r();

        Decompiler dec = new Decompiler(cfrJar, findJava());
        Map<String, DecompiledUnit> decompiled = decompileTop(prep, dec, topK);
        // class 候选数（截断后）== 反编译 map 大小；先记录，因下方 putAll(text) 会改变 decompiled
        int classCandCount = decompiled.size();
        // 文本类文件（配置文件/JSP/前端 JS/HTML/CSS）并入 stageA 摘要；stageB 深读携带 fileClass 以切换领域措辞
        Map<String, DecompiledUnit> text = buildTextMap(r, oldSnap, newSnap, topK);
        decompiled.putAll(text);

        AiConfig aiCfg = new AiConfig();
        aiCfg.setEnabled(true);
        // 公网模型模式（触发脱敏）；本地 ollama 时把 provider 改为 "ollama"
        aiCfg.setProvider("openai");
        // 有真实 API Key -> 用 HttpAiAnalyzer（真实调用）；否则离线回放 Mock
        AiAnalyzer analyzer;
        if (a.contains(ARG_APIKEY)) {
            aiCfg.setApiKey(a.get(a.indexOf(ARG_APIKEY) + 1));
            if (a.contains(ARG_PROVIDER)) aiCfg.setProvider(a.get(a.indexOf(ARG_PROVIDER) + 1));
            if (a.contains(ARG_BASEURL)) aiCfg.setBaseUrl(a.get(a.indexOf(ARG_BASEURL) + 1));
            analyzer = new HttpAiAnalyzer(aiCfg);
            LOG.log(java.util.logging.Level.INFO, "[AI] 使用 HttpAiAnalyzer（真实调用，provider={0}）", aiCfg.getProvider());
        } else {
            analyzer = new MockAiAnalyzer(replayDir);
            LOG.info("[AI] 使用 MockAiAnalyzer（离线回放，--apikey 未提供）");
        }

        // 项目级上下文增强（可选 --project <dir>）：扫描工程目录，作为 AI 分析依据
        ProjectContext ctx = loadProjectContext(a);

        // 连接测试（FR9.4 设置弹窗「测试」按钮对应）
        boolean conn = analyzer.testConnection(aiCfg);
        LOG.log(java.util.logging.Level.INFO, "[AI] 连接测试={0}", conn);

        // 阶段A
        String stageAPrompt = analyzer.buildStageAPrompt(r, decompiled, aiCfg, ctx);
        double stageATokens = analyzer.estimateTokens(stageAPrompt);
        boolean gateWarn = stageATokens > aiCfg.getCostGateWarnTokens();
        StageASummary summary;
        List<FileAnalysis> b;
        try {
            summary = analyzer.stageA(r, decompiled, aiCfg, ctx);

            // 阶段B：仅对前 topK 候选深读（含文本类文件，携带 fileClass 以切换领域措辞）
            List<AiAnalyzer.DecompileReq> bCands = new ArrayList<>();
            for (Map.Entry<String, DecompiledUnit> e : decompiled.entrySet()) {
                bCands.add(new AiAnalyzer.DecompileReq(e.getKey(), e.getValue(), fileClassOfKey(e.getKey(), oldSnap, newSnap)));
            }
            // 成本闸门：阶段B 仅对前 aiCfg.stageBTopK 深读
            if (bCands.size() > aiCfg.getStageBTopK()) bCands = bCands.subList(0, aiCfg.getStageBTopK());
            b = analyzer.stageB(bCands, aiCfg, ctx);
        } catch (RuntimeException ex) {
            // 优雅降级（需求：AI 不可用时基础比对仍可用）：不崩溃，给兜底结论
            LOG.log(java.util.logging.Level.WARNING, "[AI] 分析调用失败，已降级为基础结论：{0}", ex.getMessage());
            summary = new StageASummary();
            summary.setOverallRisk("UNKNOWN");
            summary.setImpactScope("AI 服务不可用（连接失败/超时/鉴权错误），本次仅给出基础差异结论，未做智能分析。错误：" + ex.getMessage());
            summary.setTestThemes(java.util.Arrays.asList("人工核对全部差异文件", "回归核心业务流程", "校验对外接口兼容性"));
            summary.setFileRisks(new ArrayList<>());
            b = new ArrayList<>();
        }

        System.out.println("== AI (两阶段 / 离线回放) =="); // NOSONAR
        System.out.println("项目级上下文: " + (ctx != null && !ctx.isEmpty() ? ("已启用（构建系统=" + ctx.getBuildSystem() + ", 模块数=" + ctx.getModules().size() + "）") : "未启用")); // NOSONAR
        System.out.println("阶段A: overallRisk=" + summary.getOverallRisk() + " 影响=" + summary.getImpactScope()); // NOSONAR
        if (ctx != null && summary.getContextInfluence() != null && !summary.getContextInfluence().isEmpty()) {
            System.out.println("阶段A 上下文影响: " + summary.getContextInfluence()); // NOSONAR
        }
        System.out.println("阶段A: 测试主题=" + summary.getTestThemes()); // NOSONAR
        System.out.println("阶段A prompt tokens≈" + (long) stageATokens + " 成本闸门触发=" + gateWarn + " (阈值" + aiCfg.getCostGateWarnTokens() + ")"); // NOSONAR
        System.out.println("阶段B: 深读文件数=" + b.size() + " / 候选=" + classCandCount); // NOSONAR
        for (FileAnalysis fa : b) {
            StringBuilder line = new StringBuilder();
            line.append("  - ").append(fa.getKey()).append(" | risk=").append(fa.getRisk()).append(" | intent=").append(fa.getIntent());
            if (fa.getTestPoints() != null && !fa.getTestPoints().isEmpty()) {
                line.append(" | 测试要点=").append(String.join("；", fa.getTestPoints()));
            }
            if (ctx != null && fa.getContextInfluence() != null && !fa.getContextInfluence().isEmpty()) {
                line.append(" | 上下文影响=").append(fa.getContextInfluence());
            }
            System.out.println(line.toString()); // NOSONAR
        }
        System.out.println("prompt 已落地: " + replayDir); // NOSONAR
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** 构建文本类文件内容 diff 集合（CONFIG/JSP/JS/HTML/CSS）：配置文件与 JSP 直接换行归一后 diff，
     *  压缩前端资源先美化再 diff（FR4.4 增强 + 本需求扩展）。 */
    private static Map<String, DecompiledUnit> buildTextMap(DiffResult r, PackageSnapshot oldSnap,
                                                            PackageSnapshot newSnap, int topK) {
        Map<String, DecompiledUnit> m = new LinkedHashMap<>();
        FrontendTextDiff ftd = new FrontendTextDiff();
        List<String> cands = DiffEngine.collectTextDiffCandidates(r, oldSnap, newSnap);
        int limit = Math.min(cands.size(), topK);
        for (int i = 0; i < limit; i++) {
            String k = cands.get(i);
            LogicalEntry oe = oldSnap.getEntries().get(k);
            LogicalEntry ne = newSnap.getEntries().get(k);
            FileClass fc;
            if (ne != null) {
                fc = ne.getFileClass();
            } else if (oe != null) {
                fc = oe.getFileClass();
            } else {
                fc = FileClass.JS;
            }
            m.put(k, ftd.diff(oldSnap, newSnap, oe, ne, k, fc));
        }
        return m;
    }

    private static FileClass fileClassOfKey(String k, PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        LogicalEntry e = oldSnap.getEntries().get(k);
        if (e == null) e = newSnap.getEntries().get(k);
        return (e != null) ? e.getFileClass() : FileClass.CLASS;
    }

    public static void main(String[] args) throws Exception {
        int code = cliMain(args);
        // 仅非 0 才显式退出：exit 0 走自然结束（JVM 默认码即 0），
        // 避免嵌入式调用方（TestRunner 等）被 System.exit(0) 无故终止。
        if (code != 0) System.exit(code);
    }

    /**
     * CLI 统一入口（FR-005）：返回进程退出码而不直接 System.exit——
     * 0=正常/无差异，1=有差异（仅 ci 子命令带 --fail-on-diff 时），2=用法或运行错误。
     * 与 main 分离是为了可测性：测试进程直接断言返回值，不会被 System.exit 杀死。
     */
    public static int cliMain(String[] args) {
        try {
            return dispatch(args);
        } catch (Throwable t) {
            // FR-005 语义：任何运行期失败（含 NoClassDefFoundError 等 Error，如 CFR 缺失）统一归为
            // exit 2，供 CI 门禁区分「有差异」与「跑不起来」；catch Throwable 是刻意的门禁兜底。
            System.out.println("error: " + t.getMessage()); // NOSONAR(S106) - CLI 控制台输出
            t.printStackTrace(); // NOSONAR
            return 2;
        }
    }

    private static int dispatch(String[] args) throws IOException {
        if (args.length == 0) {
            printUsage();
            return 2;
        }
        List<String> a = Arrays.asList(args);
        String cmd = args[0];
        boolean expandAll = a.contains("--expand-all");
        int topK = 12;
        if (a.contains("--top-k")) topK = Integer.parseInt(a.get(a.indexOf("--top-k") + 1));
        Path cfrJar = null;
        if (a.contains("--cfr")) cfrJar = Paths.get(a.get(a.indexOf("--cfr") + 1));
        int maxDepth = -1;
        if (a.contains("--max-depth")) maxDepth = Integer.parseInt(a.get(a.indexOf("--max-depth") + 1));
        FolderDiff.Options fopts = FolderDiff.Options.defaults();
        fopts.setMaxDepth(maxDepth);
        Path folderReport = a.contains(ARG_REPORT) ? Paths.get(a.get(a.indexOf(ARG_REPORT) + 1)) : null;

        switch (cmd) {
            case "inspect":
                requireArgs(args, 2, cmd);
                inspect(Paths.get(args[1]));
                return 0;
            case "compare":
                requireArgs(args, 3, cmd);
                compare(Paths.get(args[1]), Paths.get(args[2]), expandAll);
                return 0;
            case "compare-folders":
                requireArgs(args, 3, cmd);
                folderDiff(Paths.get(args[1]), Paths.get(args[2]), topK, fopts, null);
                return 0;
            case "folderdiff":
                requireArgs(args, 3, cmd);
                folderDiff(Paths.get(args[1]), Paths.get(args[2]), topK, fopts, folderReport);
                return 0;
            case "decompile":
                requireArgs(args, 3, cmd);
                decompile(Paths.get(args[1]), Paths.get(args[2]), expandAll, topK, cfrJar);
                return 0;
            case "report":
                requireArgs(args, 3, cmd);
                report(Paths.get(args[1]), Paths.get(args[2]), expandAll, topK, cfrJar,
                        reportOutPath(a, "bempdiff-report.md"), a);
                return 0;
            case "ci":
                // FR-005：CI 门禁入口（旗标式参数，退出码承载差异判定）
                return ci(a, topK, cfrJar);
            case "export":
                requireArgs(args, 3, cmd);
                export(Paths.get(args[1]), Paths.get(args[2]), expandAll, topK, cfrJar,
                        outPath(a, "--out", "bempdiff-export"));
                return 0;
            case "diff-jars":
                requireArgs(args, 3, cmd);
                diffJars(Paths.get(args[1]), Paths.get(args[2]), expandAll, topK, cfrJar,
                        reportOutPath(a, "bempdiff-libjar-report.md"));
                return 0;
            case "ai":
                requireArgs(args, 3, cmd);
                ai(Paths.get(args[1]), Paths.get(args[2]), expandAll, topK, cfrJar,
                        outPath(a, ARG_REPLAY, "bempdiff-ai-replay"), a);
                return 0;
            case "server": {
                int srvPort = 18765;
                Path srvWeb = null;
                if (a.contains("--port")) srvPort = Integer.parseInt(a.get(a.indexOf("--port") + 1));
                if (a.contains("--webroot")) srvWeb = Paths.get(a.get(a.indexOf("--webroot") + 1));
                com.bempdiff.server.BempServer srv =
                        new com.bempdiff.server.BempServer(
                                com.bempdiff.server.ServerConfig.defaultLocation(), srvWeb);
                srv.start(srvPort);
                return 0;
            }
            default:
                System.out.println("unknown subcommand: " + cmd); // NOSONAR
                printUsage();
                return 2;
        }
    }

    private static void printUsage() {
        System.out.println("usage:"); // NOSONAR
        System.out.println("  inspect <pkg>"); // NOSONAR
        System.out.println("  compare <old> <new> [--expand-all]"); // NOSONAR
        System.out.println("  compare-folders <leftDir> <rightDir>             (文件夹对比，基础版)"); // NOSONAR
        System.out.println("  folderdiff <leftDir> <rightDir> [--report <md>] [--max-depth N]"); // NOSONAR
        System.out.println("  decompile <old> <new> [--expand-all] [--top-k N] [--cfr <cfr.jar>]"); // NOSONAR
        System.out.println("  report  <old> <new> [--expand-all] [--top-k N] [--cfr <cfr.jar>] [--out <md>] [--ai] [--apikey KEY] [--baseurl URL] [--provider P] [--model M] [--aiconfig <path>] [--project <dir>] [--replay <dir>]"); // NOSONAR
        System.out.println("  export  <old> <new> [--expand-all] [--top-k N] [--cfr <cfr.jar>] [--out <dir>]"); // NOSONAR
        System.out.println("  ai      <old> <new> [--expand-all] [--top-k N] [--cfr <cfr.jar>] [--replay <dir>] [--apikey KEY] [--baseurl URL] [--provider P]"); // NOSONAR
        System.out.println("  diff-jars <old> <new> [--expand-all] [--top-k N] [--cfr <cfr.jar>] [--out <md>]   (Req6: 差异 lib jar 内部 class 反编译源码对比)"); // NOSONAR
        System.out.println("  server [--port N] [--webroot <dir>]   (Web UI 后端：启动内嵌 HTTP 服务，托管 SPA + REST API)"); // NOSONAR
        System.out.println("  ci --left <old> --right <new> [--report <md>] [--json <out.json>] [--fail-on-diff] [--ai] [--top-k N] [--cfr <cfr.jar>] [--expand-all]"); // NOSONAR
        System.out.println("     （FR-005 CI 门禁：exit 0=无差异 / 1=有差异(--fail-on-diff) / 2=错误；HTML 报告由桌面端/FR-009 提供）"); // NOSONAR
        System.out.println("（省略 --out/--replay 时输出到当前目录的相对路径）"); // NOSONAR
    }

    /** 取旗标参数的值；缺失返回 null（ci 子命令旗标式参数用）。 */
    private static String flagValue(List<String> a, String flag) {
        int i = a.indexOf(flag);
        return (i >= 0 && i + 1 < a.size()) ? a.get(i + 1) : null;
    }

    /**
     * FR-005：CI/CI 门禁子命令。旗标式参数对齐 PRD 形态（--left/--right），
     * 复用 {@link #report} 全链路（含可选 --ai 两阶段分析，--apikey 为空串时按 Mock 降级），
     * 额外产出 JSON 摘要（--json）并以退出码承载差异判定（--fail-on-diff）。
     * 返回：0=无差异或未启用 fail-on-diff；1=有差异且启用 fail-on-diff；2=参数缺失。
     */
    private static int ci(List<String> a, int topK, Path cfrJar) throws IOException {
        String l = flagValue(a, "--left");
        String rr = flagValue(a, "--right");
        if (l == null || rr == null) {
            System.out.println("错误: ci 需要 --left <old> 与 --right <new>"); // NOSONAR
            printUsage();
            return 2;
        }
        Path outMd = reportOutPath(a, "bempdiff-ci-report.md");
        ReportOut out = report(Paths.get(l), Paths.get(rr),
                a.contains("--expand-all"), topK, cfrJar, outMd, a);
        DiffStats s = out.s();
        boolean hasDiff = s.getAdded() + s.getDeleted() + s.getModified() > 0;

        if (a.contains("--json")) {
            Path jsonPath = Paths.get(flagValue(a, "--json"));
            if (jsonPath.getParent() != null) Files.createDirectories(jsonPath.getParent());
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("left", l);
            j.put("right", rr);
            j.put("hasDiff", hasDiff);
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("added", s.getAdded());
            st.put("deleted", s.getDeleted());
            st.put("modified", s.getModified());
            st.put("unchanged", s.getUnchanged());
            st.put("bizChanged", s.getBizChanged());
            st.put("jarChanged", s.getJarChanged());
            j.put("stats", st);
            // 差异清单按状态分组（与差异树同源的 key 口径，供 CI 侧做门禁细分规则）
            Map<String, Object> entries = new LinkedHashMap<>();
            for (DiffStatus stt : DiffStatus.values()) entries.put(stt.name().toLowerCase(), out.r().get(stt));
            j.put("entries", entries);
            if (out.summary() != null) {
                Map<String, Object> ai = new LinkedHashMap<>();
                ai.put("overallRisk", out.summary().getOverallRisk());
                ai.put("impactScope", out.summary().getImpactScope());
                // FR-013：实际消耗明细（输入/输出分段；Mock/离线为空串不写入）
                if (out.aiUsageLine() != null && !out.aiUsageLine().isEmpty()) {
                    ai.put("usage", out.aiUsageLine());
                }
                j.put("ai", ai);
            }
            // FR-007：API 兼容性分级进 JSON（CI 门禁可对不兼容项做阻断规则）
            if (out.apiCompat() != null && !out.apiCompat().isEmpty()) {
                Map<String, Object> ac = new LinkedHashMap<>();
                ac.put("removed", out.apiCompat().removed);
                ac.put("signatureChanged", out.apiCompat().sigChanged);
                ac.put("added", out.apiCompat().added);
                List<Map<String, Object>> items = new ArrayList<>();
                for (com.bempdiff.diff.ApiCompat.Item it : out.apiCompat().items) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("level", it.level.name());
                    im.put("class", it.cls);
                    im.put("api", it.api);
                    items.add(im);
                }
                ac.put("items", items);
                j.put("apiCompat", ac);
            }
            // FR-012：变更影响面进 JSON（风险计数 + 引用证据链，CI 可按 CRITICAL/HIGH 阻断）
            if (out.impact() != null && !out.impact().isEmpty()) {
                Map<String, Object> im0 = new LinkedHashMap<>();
                im0.put("maxRisk", out.impact().maxRisk().name());
                im0.put("critical", out.impact().critical);
                im0.put("high", out.impact().high);
                im0.put("medium", out.impact().medium);
                im0.put("low", out.impact().low);
                List<Map<String, Object>> iitems = new ArrayList<>();
                for (com.bempdiff.diff.ImpactAnalyzer.ImpactItem it : out.impact().items) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("risk", it.risk.name());
                    im.put("class", it.changedClass);
                    im.put("status", it.status);
                    im.put("reason", it.reason);
                    im.put("referencedBy", it.referencedBy);
                    iitems.add(im);
                }
                im0.put("items", iitems);
                j.put("impact", im0);
            }
            Files.writeString(jsonPath, com.bempdiff.server.Json.write(j) + "\n", java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("json: " + jsonPath); // NOSONAR
        }
        // 日志打印裸判定而非实际返回值：实际返回还受 --fail-on-diff 开关控制，混排会误导门禁使用者
        System.out.println("hasDiff: " + hasDiff + (a.contains("--fail-on-diff")
                ? " (fail-on-diff on, exit=" + (hasDiff ? 1 : 0) + ")"
                : " (fail-on-diff off, exit=0)")); // NOSONAR
        return (a.contains("--fail-on-diff") && hasDiff) ? 1 : 0;
    }

    /** 子命令参数个数校验：不足则打印用法并抛异常（由 cliMain 统一归为 exit 2），不再直接 System.exit。 */
    private static void requireArgs(String[] args, int need, String cmd) {
        if (args.length < need) {
            System.out.println("错误: " + cmd + " 需要至少 " + (need - 1) + " 个位置参数，实际 " + (args.length - 1)); // NOSONAR
            printUsage();
            throw new IllegalArgumentException(cmd + ": missing positional args");
        }
    }

    /** 取 --flag 后的路径；未提供则用相对默认名（避免硬编码开发机绝对路径，适配分发后的 exe）。 */
    private static Path outPath(List<String> a, String flag, String def) {
        return Paths.get(a.contains(flag) ? a.get(a.indexOf(flag) + 1) : def);
    }

    /** 报告输出路径：兼容 --out 与 --report 两种写法（历史子命令曾混用，统一为别名）。 */
    private static Path reportOutPath(List<String> a, String def) {
        for (String flag : new String[]{"--out", ARG_REPORT}) {
            if (a.contains(flag)) return Paths.get(a.get(a.indexOf(flag) + 1));
        }
        return Paths.get(def);
    }
}

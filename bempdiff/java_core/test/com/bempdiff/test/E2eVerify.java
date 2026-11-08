package com.bempdiff.test;

import com.bempdiff.config.ParseConfig;
import com.bempdiff.diff.DiffEngine;
import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.diff.DiffStatus;
import com.bempdiff.export.AssetExporter;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.parse.PackageParser;
import com.bempdiff.server.CompareOptions;
import com.bempdiff.unpack.NestedUnpacker;
import com.bempdiff.unpack.UnpackOptions;
import com.bempdiff.unpack.UnpackReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 端到端校验：用与 BempServer 一致的 CompareOptions 默认解包参数跑真实 M059/M061，
 * 交叉核对「统计差异数 / 差异树(去重后) / 导出zip / 破坏性(删除)」各视角的数量是否一致，
 * 并与 DiagWar 的宽松解包结果(84/86/6/24617)对比，暴露默认解包参数下的偏差。
 */
public final class E2eVerify {
    public static void main(String[] args) throws Exception {
        // 路径参数化：可用 -Dbempdiff.oldZip= -Dbempdiff.newZip= -Dbempdiff.workDir= 覆盖；
        // 缺省回退到作者本机样例，便于复用。因依赖外部大压缩包，不纳入 TestRunner（仅供手动诊断）。
        String oldZ = System.getProperty("bempdiff.oldZip",
                "E:\\testC\\BEMP5.0V202301-02-036M059(20260703-1104).zip");
        String newZ = System.getProperty("bempdiff.newZip",
                "E:\\testC\\BEMP5.0V202301-02-036M061(20260707-1135).zip");
        String workArg = System.getProperty("bempdiff.workDir",
                "bempdiff/java_core/.e2e-tmp"); // 相对项目根，避免硬编码绝对路径

        // 与 BempServer /api/session/compare 完全一致：CompareOptions 默认 → toUnpackOptions，开启 unpackNested
        CompareOptions opts = CompareOptions.fromRequest(Map.of("options", Map.of("unpackNested", true)));
        ParseConfig pc = opts.toParseConfig();
        PackageParser pp = new PackageParser();
        PackageSnapshot os = pp.parse(Paths.get(oldZ), pc, false);
        PackageSnapshot ns = pp.parse(Paths.get(newZ), pc, false);
        UnpackOptions uo = opts.toUnpackOptions();
        System.out.println("解包参数: threads=" + uo.threadPoolSize + " perItem=" + uo.perItemTimeoutMs
                + "ms maxDepth=" + uo.maxDepth + " totalCapMB=" + (uo.totalBytesCap / 1024 / 1024));

        Path base = Paths.get(workArg);
        // 清掉上次残留，避免 temp 盘被累积的 WAR 解包/导出文件塞满
        if (Files.exists(base)) { try (Stream<Path> st = Files.walk(base)) { st.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} }); } }
        Files.createDirectories(base);
        Path tro = base.resolve("old");
        Path trn = base.resolve("new");
        // 与 BempServer 一致：本作业两侧共享一个作业级 memo 预算（修复二次比对饿死）
        NestedUnpacker.MemoBudget memoBudget = new NestedUnpacker.MemoBudget();
        PackageSnapshot fo = new NestedUnpacker(uo, tro, memoBudget).flatten(os, new UnpackReport("old"), null);
        String dumpE = System.getProperty("bempdiff.dumpOld", "");
        if (!dumpE.isEmpty()) {
            java.util.List<String> lines = fo.getEntries().keySet().stream().sorted()
                    .map(k -> k + "\t" + fo.getEntries().get(k).getSize())
                    .collect(java.util.stream.Collectors.toList());
            Files.write(Paths.get(dumpE), lines);
            System.out.println("E2E dumped old keys (" + lines.size() + ") -> " + dumpE);
        }
        System.out.println("OLD 扁平化条目=" + fo.getEntries().size()
                + "  含.war键=" + fo.getEntries().keySet().stream().filter(k -> k.contains(".war")).count());
        PackageSnapshot fn = new NestedUnpacker(uo, trn.resolve("new"), memoBudget).flatten(ns, new UnpackReport("new"), null);
        System.out.println("NEW 扁平化条目=" + fn.getEntries().size()
                + "  含.war键=" + fn.getEntries().keySet().stream().filter(k -> k.contains(".war")).count());

        DiffEngine engine = new DiffEngine();
        DiffResult r = engine.compute(fo, fn);
        DiffStats s = engine.stats(r);
        int added = s.getAdded(), deleted = s.getDeleted(), modified = s.getModified(), unchanged = s.getUnchanged();
        int total = added + deleted + modified + unchanged;
        System.out.println("== 统计(engine.stats) ==");
        System.out.println("新增=" + added + " 删除=" + deleted + " 修改=" + modified + " 未变=" + unchanged
                + " 合计=" + total + " bizChanged=" + s.getBizChanged() + " jarChanged=" + s.getJarChanged());

        // 差异树去重(与 BempServer.buildTree 同口径)：unpackNested 下跳过扁平化嵌套后代
        int treeA = 0, treeD = 0, treeM = 0, treeU = 0;
        for (String k : r.get(DiffStatus.ADDED))  if (!isFlattenedDescendant(k, fo, fn)) treeA++;
        for (String k : r.get(DiffStatus.DELETED)) if (!isFlattenedDescendant(k, fo, fn)) treeD++;
        for (String k : r.get(DiffStatus.MODIFIED)) if (!isFlattenedDescendant(k, fo, fn)) treeM++;
        for (String k : r.get(DiffStatus.UNCHANGED)) if (!isFlattenedDescendant(k, fo, fn)) treeU++;
        System.out.println("== 差异树(去重后) ==");
        System.out.println("ADD=" + treeA + " DEL=" + treeD + " MOD=" + treeM + " UNCH=" + treeU);
        System.out.println("[比对] 树==统计? ADD:" + (treeA == added) + " DEL:" + (treeD == deleted)
                + " MOD:" + (treeM == modified) + " UNCH:" + (treeU == unchanged));

        // 导出差异资产：与 BempServer 新导出流程一致 exportIncrement + zipTree（写到 D 盘，避免 temp 盘不足）
        Path xroot = base.resolve("export");
        new AssetExporter().exportIncrement(r, fo, fn, xroot);
        Path zip = new AssetExporter().zipTree(xroot, xroot.resolve("bempdiff-export.zip"));
        long incFiles = countFiles(xroot.resolve("increment"));
        long delFiles = countFiles(xroot.resolve("deleted"));
        // 导出写盘逐字节计数（统计中的真实写入数）
        int expectInc = added + modified;
        System.out.println("== 导出差异资产 ==");
        System.out.println("increment/ 文件数=" + incFiles + " (期望=" + expectInc + ") deleted/ 文件数=" + delFiles
                + " (期望=" + deleted + ") zip字节=" + Files.size(zip));
        System.out.println("[比对] 导出increment==新增+修改? " + (incFiles == expectInc)
                + "  导出deleted==删除? " + (delFiles == deleted));

        // 破坏性(删除类)：MarkdownReport.renderBreakingChanges 口径 = DELETED 集合
        System.out.println("== 破坏性(删除类) ==");
        System.out.println("DELETED 集合大小=" + deleted + "（即破坏性变更清单项数，含 jar/class/资源，非仅 L1 class）");

        // —— 报告数字一致性（AI/报告差异树与本统计同源）——
        String md = new com.bempdiff.report.MarkdownReport(12)
                .render(fo, fn, r, s, java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
        String statNeedle = "新增 **" + added + "** · 删除 **" + deleted + "** · 修改 **" + modified + "** · 未变 " + unchanged;
        int reportTree = countBullets(between(md, "## 二、差异文件树", "## 三、"));
        int reportBreaking = countBullets(between(md, "## 六、破坏性变更清单", "## 七、"));
        System.out.println("== 报告数字一致性 ==");
        System.out.println("报告统计行含[新增/删除/修改/未变]? " + md.contains(statNeedle)
                + "  差异树清单行=" + reportTree + "(应=" + (added + deleted + modified) + ")"
                + "  破坏性清单行=" + reportBreaking + "(应=" + deleted + ")");
        System.out.println("[比对] 报告统计/清单/破坏性全部与统计一致? "
                + (md.contains(statNeedle) && reportTree == (added + deleted + modified) && reportBreaking == deleted));

        // 全量差异 key 抽样打印，供人工核对是否已无「同内容假阳性」
        System.out.println("== 差异 key（ADD/DEL/MOD 全体，" + (added + deleted + modified) + " 项） ==");
        for (String k : r.get(DiffStatus.DELETED)) System.out.println("DEL " + k);
        for (String k : r.get(DiffStatus.ADDED)) System.out.println("ADD " + k);
        for (String k : r.get(DiffStatus.MODIFIED)) System.out.println("MOD " + k);
    }

    private static boolean isFlattenedDescendant(String key, PackageSnapshot oldSnap, PackageSnapshot newSnap) {
        int idx = key.indexOf('/');
        while (idx >= 0) {
            String prefix = key.substring(0, idx);
            if (isArchiveContainer(prefix, oldSnap) || isArchiveContainer(prefix, newSnap)) return true;
            if (prefix.endsWith("!")) {
                String cont = prefix.substring(0, prefix.length() - 1);
                if (isArchiveContainer(cont, oldSnap) || isArchiveContainer(cont, newSnap)) return true;
            }
            idx = key.indexOf('/', idx + 1);
        }
        return false;
    }

    private static boolean isArchiveContainer(String key, PackageSnapshot snap) {
        if (snap == null) return false;
        LogicalEntry e = snap.getEntries().get(key);
        if (e == null) return false;
        FileClass fc = e.getFileClass();
        return fc == FileClass.ARCHIVE || fc == FileClass.JAR;
    }

    private static long countFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) return -1;
        try (Stream<Path> st = Files.walk(dir)) {
            return st.filter(Files::isRegularFile).count();
        }
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        if (i < 0) return "";
        int j = s.indexOf(end, i);
        return (j < 0) ? s.substring(i) : s.substring(i, j);
    }

    private static int countBullets(String section) {
        int c = 0;
        for (String line : section.split("\n")) if (line.trim().startsWith("- ")) c++;
        return c;
    }
}
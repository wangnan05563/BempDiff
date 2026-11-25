package com.bempdiff.test;

import com.bempdiff.diff.DiffResult;
import com.bempdiff.diff.DiffStats;
import com.bempdiff.model.EntrySource;
import com.bempdiff.model.FileClass;
import com.bempdiff.model.Layer;
import com.bempdiff.model.LogicalEntry;
import com.bempdiff.model.PackageSnapshot;
import com.bempdiff.model.PackageType;
import com.bempdiff.server.CompareOptions;
import com.bempdiff.server.Job;
import com.bempdiff.unpack.NestedUnpacker;
import com.bempdiff.unpack.UnpackReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Job} 状态机与字段方法单测（无后台线程，直接驱动 markRunning/complete/fail/markCancelled/
 * releaseMemory/cleanupRuntime 等的守卫分支）。补齐 Job 此前只被间接（UnpackTest 仅测 setUnpackReports/
 * cleanupRuntime 目录回收）覆盖而遗漏的状态转换守卫、终态幂等、内存回扣与运行时目录清理的多个分支。
 */
public final class JobStateTest {

    private static Job newJob() {
        return new Job("J-" + System.nanoTime(), "package", new CompareOptions());
    }

    private static DiffResult emptyResult() {
        return new DiffResult(new LinkedHashMap<String, LogicalEntry>(), new LinkedHashMap<String, LogicalEntry>());
    }

    // ---------------- 默认状态与基础 getter ----------------

    public void testDefaultsAndBasicGetters() {
        Job job = newJob();
        Asserts.assertEquals("mode 原样持有", "package", job.mode);
        Asserts.assertNotNull("opts 非空", job.opts);
        Asserts.assertEquals("初始状态 QUEUED", "QUEUED", job.getStatus());
        Asserts.assertEquals("初始阶段 queued", "queued", job.getPhase());
        Asserts.assertEquals("初始消息 等待中", "等待中", job.getMessage());
        Asserts.assertEquals("初始进度 0", 0, job.getProgress());
        Asserts.assertNull("初始 error null", job.getError());
        Asserts.assertNull("初始 oldSnap null", job.getOldSnap());
        Asserts.assertNull("初始 newSnap null", job.getNewSnap());
        Asserts.assertNull("初始 result null", job.getResult());
        Asserts.assertNull("初始 stats null", job.getStats());
        Asserts.assertNull("初始 unpackReports null", job.getUnpackReports());
        Asserts.assertNull("初始 runtimeDir null", job.getRuntimeDir());
        Asserts.assertFalse("初始未请求取消", job.isCancelRequested());
        Asserts.assertEquals("初始 finishedAtMillis=0", 0L, job.finishedAtMillis);
    }

    // ---------------- markRunning ----------------

    public void testMarkRunning_updatesProgressWhenNonNegative() {
        Job job = newJob();
        job.markRunning("parsing", "解析包…", 30);
        Asserts.assertEquals("进入 RUNNING", "RUNNING", job.getStatus());
        Asserts.assertEquals("阶段透传", "parsing", job.getPhase());
        Asserts.assertEquals("消息透传", "解析包…", job.getMessage());
        Asserts.assertEquals("进度更新为 30", 30, job.getProgress());
    }

    public void testMarkRunning_negativeProgressKeepsOldValue() {
        Job job = newJob();
        job.markRunning("parsing", "a", 40);
        job.markRunning("diffing", "b", -1); // progress<0 不更新进度值
        Asserts.assertEquals("阶段仍更新", "diffing", job.getPhase());
        Asserts.assertEquals("消息仍更新", "b", job.getMessage());
        Asserts.assertEquals("负进度不覆盖旧值(仍 40)", 40, job.getProgress());
    }

    /** M-11：QUEUED 取消应立即终态；后台线程随后 markRunning 不得把终态拉回 RUNNING。 */
    public void testCancelQueuedThenMarkRunningDoesNotResurrect() {
        Job job = newJob();
        job.requestCancel();
        job.markCancelled();
        Asserts.assertEquals("QUEUED 取消应立即 CANCELLED", "CANCELLED", job.getStatus());
        Asserts.assertTrue("应记录结束时间", job.finishedAtMillis > 0);
        job.markRunning("parsing", "解析包…", 10);
        Asserts.assertEquals("取消后 markRunning 不应复活作业", "CANCELLED", job.getStatus());
        Asserts.assertEquals("阶段保持 cancelled", "cancelled", job.getPhase());
        job.complete(null, null, emptyResult(), new DiffStats());
        Asserts.assertEquals("取消后 complete 不应覆盖终态", "CANCELLED", job.getStatus());
    }

    // ---------------- appendMessage ----------------

    public void testAppendMessage_nonEmptyBaseJoinsWithSeparator() {
        Job job = newJob(); // message="等待中"（非空）
        job.appendMessage("解包不完整");
        Asserts.assertEquals("非空基串以中文分号拼接", "等待中；解包不完整", job.getMessage());
    }

    public void testAppendMessage_emptyBaseTakesExtra() {
        Job job = newJob();
        job.markRunning("p", "", 10); // 使 message 变为空串
        job.appendMessage("仅警告");
        Asserts.assertEquals("空基串直接取附加内容", "仅警告", job.getMessage());
    }

    public void testAppendMessage_nullOrEmptyIsNoop() {
        Job job = newJob();
        String before = job.getMessage();
        job.appendMessage(null);
        Asserts.assertEquals("null 附加不改消息", before, job.getMessage());
        job.appendMessage("");
        Asserts.assertEquals("空附加不改消息", before, job.getMessage());
    }

    // ---------------- complete ----------------

    public void testComplete_fromQueuedIsIgnored() {
        Job job = newJob();
        job.complete(null, null, emptyResult(), new DiffStats());
        Asserts.assertEquals("非 RUNNING 时 complete 被忽略", "QUEUED", job.getStatus());
        Asserts.assertNull("未回填 result", job.getResult());
    }

    public void testComplete_fromRunningSetsDone() {
        Job job = newJob();
        job.markRunning("building", "生成报告…", 80);
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        PackageSnapshot os = new PackageSnapshot(null, PackageType.JAR, "1", m);
        PackageSnapshot ns = new PackageSnapshot(null, PackageType.JAR, "2", m);
        DiffStats stats = new DiffStats();
        stats.setAdded(3);
        DiffResult result = emptyResult();
        job.complete(os, ns, result, stats);
        Asserts.assertEquals("RUNNING→DONE", "DONE", job.getStatus());
        Asserts.assertEquals("阶段 done", "done", job.getPhase());
        Asserts.assertEquals("消息 完成", "完成", job.getMessage());
        Asserts.assertEquals("进度 100", 100, job.getProgress());
        Asserts.assertEquals("回填 oldSnap", os, job.getOldSnap());
        Asserts.assertEquals("回填 newSnap", ns, job.getNewSnap());
        Asserts.assertEquals("回填 result", result, job.getResult());
        Asserts.assertEquals("回填 stats", stats, job.getStats());
        Asserts.assertTrue("DONE 记录结束时间戳", job.finishedAtMillis > 0L);
    }

    // ---------------- fail ----------------

    public void testFail_fromQueuedIgnored() {
        Job job = newJob();
        job.fail("boom");
        Asserts.assertEquals("非 RUNNING 时 fail 被忽略", "QUEUED", job.getStatus());
        Asserts.assertNull("未记录 error", job.getError());
    }

    public void testFail_fromRunningSetsError() {
        Job job = newJob();
        job.markRunning("diffing", "计算差异…", 50);
        job.fail("解析异常");
        Asserts.assertEquals("RUNNING→ERROR", "ERROR", job.getStatus());
        Asserts.assertEquals("阶段 error", "error", job.getPhase());
        Asserts.assertEquals("消息 失败", "失败", job.getMessage());
        Asserts.assertEquals("error 记录", "解析异常", job.getError());
        Asserts.assertTrue("ERROR 记录结束时间戳", job.finishedAtMillis > 0L);
    }

    // ---------------- markCancelled（P1-E：QUEUED/RUNNING 均可取消，终态幂等）----------------

    public void testMarkCancelled_fromQueued() {
        Job job = newJob();
        job.markCancelled();
        Asserts.assertEquals("QUEUED 可被取消", "CANCELLED", job.getStatus());
        Asserts.assertEquals("阶段 cancelled", "cancelled", job.getPhase());
        Asserts.assertEquals("消息 已取消", "已取消", job.getMessage());
        Asserts.assertTrue("CANCELLED 记录结束时间戳", job.finishedAtMillis > 0L);
    }

    public void testMarkCancelled_fromRunning() {
        Job job = newJob();
        job.markRunning("parsing", "解析…", 10);
        job.markCancelled();
        Asserts.assertEquals("RUNNING 可被取消", "CANCELLED", job.getStatus());
    }

    public void testMarkCancelled_isIdempotentOnTerminal() {
        Job job = newJob();
        job.markRunning("parsing", "解析…", 10);
        job.complete(null, null, emptyResult(), new DiffStats()); // DONE
        job.markCancelled(); // 终态不应被覆盖
        Asserts.assertEquals("DONE 不被取消覆盖", "DONE", job.getStatus());
        Asserts.assertEquals("阶段仍为 done", "done", job.getPhase());
    }

    public void testMarkCancelled_afterFailStaysError() {
        Job job = newJob();
        job.markRunning("p", "x", 1);
        job.fail("err");
        job.markCancelled();
        Asserts.assertEquals("ERROR 不被取消覆盖", "ERROR", job.getStatus());
    }

    // ---------------- requestCancel ----------------

    public void testRequestCancel_setsFlag() {
        Job job = newJob();
        Asserts.assertFalse("初始未置位", job.isCancelRequested());
        job.requestCancel();
        job.requestCancel(); // 幂等
        Asserts.assertTrue("置位取消请求标记", job.isCancelRequested());
    }

    // ---------------- setUnpackReports ----------------

    public void testUnpackReportsSetGet() {
        Job job = newJob();
        UnpackReport r0 = new UnpackReport("war");
        UnpackReport r1 = new UnpackReport("war");
        job.setUnpackReports(new UnpackReport[]{r0, r1});
        Asserts.assertNotNull("报告已设置", job.getUnpackReports());
        Asserts.assertEquals("两侧报告按序", 2, job.getUnpackReports().length);
        Asserts.assertEquals("旧侧引用", r0, job.getUnpackReports()[0]);
        Asserts.assertEquals("新侧引用", r1, job.getUnpackReports()[1]);
        job.setUnpackReports(null);
        Asserts.assertNull("可回置 null", job.getUnpackReports());
    }

    // ---------------- releaseMemory（T00425/426：回扣全局 memo 计数 + 幂等）----------------

    public void testReleaseMemory_deductsGlobalMemoAndIsIdempotent() {
        byte[] data = new byte[1024];
        long before = NestedUnpacker.globalMemoBytesNow();
        NestedUnpacker.tryMemoize(data); // 成功路径：全局计数按实值累加 1024（CAS 预扣，拒绝路径不加分）
        Asserts.assertEquals("登记后全局 memo 增加 1024", before + 1024, NestedUnpacker.globalMemoBytesNow());

        LogicalEntry le = new LogicalEntry("a.txt", Layer.L0, FileClass.OTHER,
                data.length, "sha", EntrySource.memoryBacked(data));
        Map<String, LogicalEntry> m = new LinkedHashMap<>();
        m.put("a.txt", le);
        PackageSnapshot snap = new PackageSnapshot(null, PackageType.JAR, "1", m);
        snap.setMemoizedBytes(NestedUnpacker.memoizedBytesOf(snap)); // ==1024

        Job job = newJob();
        job.markRunning("parsing", "解析…", 10);
        job.complete(snap, null, emptyResult(), new DiffStats()); // 仅 oldSnap 有 memo

        long beforeRelease = NestedUnpacker.globalMemoBytesNow();
        job.releaseMemory();
        Asserts.assertEquals("释放按登记值回扣 1024", beforeRelease - 1024, NestedUnpacker.globalMemoBytesNow());
        Asserts.assertNull("oldSnap 引用置空", job.getOldSnap());
        Asserts.assertNull("newSnap 引用置空", job.getNewSnap());
        Asserts.assertNotNull("纯元数据 result 保留", job.getResult());

        long afterFirst = NestedUnpacker.globalMemoBytesNow();
        job.releaseMemory(); // 幂等：memoryReleased 已置位，不再重复回扣
        Asserts.assertEquals("二次释放不再回扣", afterFirst, NestedUnpacker.globalMemoBytesNow());
    }

    public void testReleaseMemory_noSnapshotsIsSafe() {
        Job job = newJob();
        long before = NestedUnpacker.globalMemoBytesNow();
        job.releaseMemory(); // oldSnap/newSnap 皆 null：循环体跳过，但仍置位 memoryReleased
        Asserts.assertEquals("无快照时不改全局计数", before, NestedUnpacker.globalMemoBytesNow());
    }

    // ---------------- runtimeDir / cleanupRuntime 各分支 ----------------

    public void testRuntimeDirGetSet() {
        Job job = newJob();
        Path p = new java.io.File("X").toPath();
        job.setRuntimeDir(p);
        Asserts.assertEquals("runtimeDir 透传", p, job.getRuntimeDir());
    }

    public void testCleanupRuntime_nullDirIsNoop() {
        Job job = newJob();
        job.cleanupRuntime(); // d==null 早返回
        Asserts.assertNull("空目录清理后仍 null", job.getRuntimeDir());
    }

    public void testCleanupRuntime_nonExistentDirIsNoop() throws Exception {
        Job job = newJob();
        Path missing = Files.createTempDirectory("bempdiff-jt-missing");
        Files.delete(missing); // 制造“不存在”路径
        job.setRuntimeDir(missing);
        job.cleanupRuntime();
        Asserts.assertNull("清理后 runtimeDir 置空", job.getRuntimeDir());
        Asserts.assertFalse("目录本就缺失，仍不存在", Files.exists(missing));
    }

    public void testCleanupRuntime_singleFileDeleted() throws Exception {
        Path file = Files.createTempFile("bempdiff-jt-file", ".bin");
        Files.write(file, new byte[]{1, 2, 3});
        Job job = newJob();
        job.setRuntimeDir(file);
        job.cleanupRuntime(); // 非目录：走 Files.deleteIfExists 分支
        Asserts.assertFalse("单文件运行时应被删除", Files.exists(file));
        Asserts.assertNull("单文件清理后置空", job.getRuntimeDir());
    }

    public void testCleanupRuntime_directoryRecursivelyDeleted() throws Exception {
        Path dir = Files.createTempDirectory("bempdiff-jt-dir");
        Files.createDirectories(dir.resolve("sub"));
        Files.write(dir.resolve("sub/x.bin"), new byte[]{9});
        Files.write(dir.resolve("y.bin"), new byte[]{8});
        Job job = newJob();
        job.setRuntimeDir(dir);
        job.cleanupRuntime(); // 目录：walk+逆序删除
        Asserts.assertFalse("运行时目录应被整体递归删除", Files.exists(dir));
    }
}

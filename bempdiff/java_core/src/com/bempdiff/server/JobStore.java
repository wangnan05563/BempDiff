package com.bempdiff.server;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 比对任务的内存注册表（单进程内，按 jobId 检索）。 */
public final class JobStore {
    /** P1-E 任务上限：超过后淘汰「已结束」的最旧任务，阻断长 soak 下私有内存 ~1.17GB 持续累积 / OOM 风险（性能报告 §8#5）。 */
    public static final int MAX_JOBS = 200;
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile Consumer<Job> onEvict = j -> { };

    /** 淘汰回调：让持有作业派生缓存的调用方同步清理，避免缓存按 jobId 无界增长。 */
    public void setOnEvict(Consumer<Job> callback) {
        this.onEvict = callback == null ? j -> { } : callback;
    }

    public Job put(Job job) {
        jobs.put(job.id, job);
        evictIfNeeded();
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public Map<String, Job> all() {
        return jobs;
    }

    /** P1-E：堆超上限时按「结束时间升序」淘汰已结束（DONE/ERROR/CANCELLED）任务，绝不淘汰进行中（QUEUED/RUNNING）任务。 */
    private void evictIfNeeded() {
        if (jobs.size() <= MAX_JOBS) return;
        List<Job> finished = jobs.values().stream()
                .filter(j -> isFinished(j.getStatus()))
                .sorted(Comparator.comparingLong(j -> j.finishedAtMillis))
                .collect(Collectors.toList());
        for (Job j : finished) {
            if (jobs.size() <= MAX_JOBS) break;
            if (jobs.remove(j.id, j)) {
                try {
                    onEvict.accept(j);
                } catch (RuntimeException ignored) {
                    // 缓存清理失败不影响任务淘汰
                }
                j.cleanupRuntime(); // 淘汰即回收该作业解包临时目录，防磁盘垃圾累积
                j.releaseMemory();  // T00425/426：淘汰时释放快照驻留并回扣全局 memo 计数
            }
        }
    }

    private static boolean isFinished(String status) {
        return !"QUEUED".equals(status) && !"RUNNING".equals(status);
    }
}
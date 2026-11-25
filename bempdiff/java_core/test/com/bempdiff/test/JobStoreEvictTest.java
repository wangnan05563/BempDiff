package com.bempdiff.test;

import com.bempdiff.server.CompareOptions;
import com.bempdiff.server.Job;
import com.bempdiff.server.JobStore;

import java.util.concurrent.atomic.AtomicInteger;

/** M-06：JobStore 淘汰作业必须触发派生缓存清理回调，且不影响任务淘汰本身。 */
public final class JobStoreEvictTest {

    public void testEvictionInvokesCacheCleanupCallback() {
        JobStore store = new JobStore();
        AtomicInteger evicted = new AtomicInteger();
        store.setOnEvict(j -> {
            Asserts.assertNotNull("淘汰回调应拿到作业", j);
            evicted.incrementAndGet();
        });
        for (int i = 0; i < JobStore.MAX_JOBS + 5; i++) {
            Job j = new Job("evict-" + i, "package", new CompareOptions());
            j.requestCancel();
            j.markCancelled(); // 进入终态，具备淘汰资格
            store.put(j);
        }
        Asserts.assertTrue("任务数应不超过上限，实测 " + store.all().size(), store.all().size() <= JobStore.MAX_JOBS);
        Asserts.assertTrue("至少应触发一次缓存清理回调，实测 " + evicted.get(), evicted.get() >= 5);
    }
}

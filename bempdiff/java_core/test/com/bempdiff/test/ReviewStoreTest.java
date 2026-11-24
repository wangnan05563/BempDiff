package com.bempdiff.test;

import com.bempdiff.server.ReviewStatusCalculator;
import com.bempdiff.server.ReviewStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T06218 文件级审阅存储单测：round-trip、状态聚合（含手动覆盖）、非法 jobId、
 * 损坏 JSON 备份降级、1000 文件摘要性能（<200ms）。
 */
public final class ReviewStoreTest {

    public void testRoundTripAggregatesHunksAndManualOverride() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-review");
        try {
            ReviewStore store = new ReviewStore(tmp.resolve("reviews"));
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("a/Foo.class", file(2, hunk("0", "reviewed", "1", "unreviewed"), false, null));
            files.put("a/Bar.class", file(2, hunk("0", "reviewed", "1", "reviewed"), false, null));
            files.put("a/Baz.class", file(5, hunk("0", "unreviewed"), true, "reviewed"));
            files.put("a/Qux.class", file(2, hunk("0", "reviewed", "1", "needs_review"), false, null));
            body.put("files", files);

            Map<String, Object> saved = store.save("job-abc_123", body);
            Map<String, Object> summary = ReviewStatusCalculator.summarize(saved);
            Asserts.assertEquals("totalFiles", 4, summary.get("totalFiles"));
            Asserts.assertEquals("reviewedFiles", 2, summary.get("reviewedFiles"));
            Asserts.assertEquals("inProgressFiles", 1, summary.get("inProgressFiles"));
            Asserts.assertEquals("needsReviewFiles", 1, summary.get("needsReviewFiles"));
            Asserts.assertEquals("unreviewedFiles", 0, summary.get("unreviewedFiles"));
            Asserts.assertEquals("manualFiles", 1, summary.get("manualFiles"));

            ReviewStore.LoadResult loaded = store.load("job-abc_123");
            Asserts.assertTrue("应存在", loaded.exists);
            Map<?, ?> outFiles = (Map<?, ?>) loaded.doc.get("files");
            Asserts.assertEquals("Foo 部分已审", "in_progress", status(outFiles, "a/Foo.class"));
            Asserts.assertEquals("Bar 全审", "reviewed", status(outFiles, "a/Bar.class"));
            Asserts.assertEquals("Baz 手动覆盖优先", "reviewed", status(outFiles, "a/Baz.class"));
            Asserts.assertEquals("Qux Hunk 需复核", "needs_review", status(outFiles, "a/Qux.class"));
            Asserts.assertEquals("schemaVersion", "1.0", loaded.doc.get("schemaVersion"));
            Asserts.assertContains("updatedAt 应为 ISO", String.valueOf(loaded.doc.get("updatedAt")), "T");
        } finally {
            deleteTree(tmp);
        }
    }

    public void testInvalidJobIdRejected() throws Exception {
        Asserts.assertFalse("空 jobId", ReviewStore.isValidJobId(""));
        Asserts.assertFalse("路径穿越", ReviewStore.isValidJobId("../escape"));
        Asserts.assertFalse("斜杠", ReviewStore.isValidJobId("a/b"));
        Asserts.assertFalse("反斜杠", ReviewStore.isValidJobId("a\\b"));
        Asserts.assertFalse("超长", ReviewStore.isValidJobId("x".repeat(65)));
        Asserts.assertTrue("UUID 合法", ReviewStore.isValidJobId("d6dbd8da-c434-442d-8594-c0443b80c8f9"));
        try {
            new ReviewStore(Files.createTempDirectory("bdf-review-bad")).save("../escape", new LinkedHashMap<>());
            Asserts.fail("非法 jobId 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Asserts.assertContains("应说明非法", expected.getMessage(), "jobId");
        }
    }

    public void testCorruptJsonBackedUpAndDegrades() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-review-corrupt");
        try {
            Path reviews = Files.createDirectories(tmp.resolve("reviews"));
            Files.writeString(reviews.resolve("job-corrupt.json"), "{bad json", StandardCharsets.UTF_8);
            ReviewStore store = new ReviewStore(reviews);
            ReviewStore.LoadResult r = store.load("job-corrupt");
            Asserts.assertFalse("损坏文件不应报告存在", r.exists);
            Asserts.assertTrue("应触发备份", r.corruptBackup);
            Asserts.assertEquals("降级为空文档", 0, ((Map<?, ?>) r.doc.get("files")).size());
            try (Stream<Path> list = Files.list(reviews)) {
                Asserts.assertTrue("应留下 .bad- 备份", list.anyMatch(p -> p.getFileName().toString().contains(".bad-")));
            }
        } finally {
            deleteTree(tmp);
        }
    }

    public void testThousandFilesSummaryUnder200ms() {
        Map<String, Object> files = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> hunks = new LinkedHashMap<>();
            for (int h = 0; h < 10; h++) {
                hunks.put(String.valueOf(h), h < (i % 11) ? "reviewed" : "unreviewed");
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("status", i % 2 == 0 ? "reviewed" : "unreviewed");
            f.put("manualOverride", false);
            f.put("hunkTotal", 10);
            f.put("hunks", hunks);
            files.put("dir/file-" + i + ".class", f);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("files", files);

        for (int warm = 0; warm < 5; warm++) ReviewStatusCalculator.summarize(doc);

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            Map<String, Object> summary = ReviewStatusCalculator.summarize(doc);
            long cost = System.nanoTime() - t0;
            best = Math.min(best, cost);
            Asserts.assertEquals("totalFiles", 1000, summary.get("totalFiles"));
            Asserts.assertEquals("totalHunks", 10000, summary.get("totalHunks"));
        }
        Asserts.assertTrue("1000 文件摘要应 <200ms，实测 " + (best / 1_000_000.0) + "ms", best < 200_000_000L);
    }

    public void testEmptyHunksNotAutoReviewed() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hunkTotal", 0);
        f.put("hunks", new LinkedHashMap<>());
        f.put("manualOverride", false);
        Map<String, Object> out = ReviewStatusCalculator.normalizeFile(f);
        Asserts.assertEquals("无 Hunk 不应自动全审", "unreviewed", out.get("status"));

        f.put("manualOverride", true);
        f.put("status", "reviewed");
        Asserts.assertEquals("手动可标已审", "reviewed", ReviewStatusCalculator.normalizeFile(f).get("status"));
    }

    private static Map<String, Object> file(int total, Map<String, Object> hunks, boolean manual, String status) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hunkTotal", total);
        f.put("hunks", hunks);
        f.put("manualOverride", manual);
        if (status != null) f.put("status", status);
        f.put("lines", java.util.List.of(1, 2));
        return f;
    }

    private static Map<String, Object> hunk(String... kv) {
        Map<String, Object> h = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) h.put(kv[i], kv[i + 1]);
        return h;
    }

    private static Object status(Map<?, ?> files, String key) {
        return ((Map<?, ?>) files.get(key)).get("status");
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }
}

package com.bempdiff.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * T06218 文件级审阅文档持久化：{@code ~/.bempdiff/reviews/{jobId}.json}。
 *
 * <p>安全与可靠性约定：
 * - jobId 只允许 {@code [A-Za-z0-9_-]}，防止路径穿越；
 * - 先写同目录临时文件再原子 move，进程中断不会留下半截 JSON；
 * - 读到损坏 JSON 时改名备份 {@code .bad-<ts>}，返回空文档而不是阻塞工作区；
 * - 按 jobId 串行读写（本地单用户场景，避免并发 PUT 互相覆盖）。
 */
public final class ReviewStore {

    public static final long MAX_BODY_BYTES = 8L * 1024 * 1024;
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final DateTimeFormatter BAD_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.systemDefault());

    private final Path dir;

    public ReviewStore() {
        this(defaultDirectory());
    }

    public ReviewStore(Path dir) {
        this.dir = dir;
    }

    public static Path defaultDirectory() {
        String override = System.getProperty("bempdiff.reviewDir", "").trim();
        if (!override.isEmpty()) return Path.of(override);
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".bempdiff", "reviews");
    }

    public Path directory() {
        return dir;
    }

    public static boolean isValidJobId(String jobId) {
        return jobId != null && JOB_ID.matcher(jobId).matches();
    }

    public Path fileFor(String jobId) {
        requireJobId(jobId);
        return dir.resolve(jobId + ".json");
    }

    /** 读取结果：exists=false 表示从未保存过（前端据此触发旧 localStorage 迁移）。 */
    public static final class LoadResult {
        public final Map<String, Object> doc;
        public final boolean exists;
        public final boolean corruptBackup;

        LoadResult(Map<String, Object> doc, boolean exists, boolean corruptBackup) {
            this.doc = doc;
            this.exists = exists;
            this.corruptBackup = corruptBackup;
        }
    }

    public synchronized LoadResult load(String jobId) throws IOException {
        requireJobId(jobId);
        Path file = fileFor(jobId);
        if (!Files.isRegularFile(file)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("schemaVersion", ReviewStatusCalculator.SCHEMA_VERSION);
            empty.put("jobId", jobId);
            empty.put("files", new LinkedHashMap<>());
            return new LoadResult(ReviewStatusCalculator.normalizeDocument(jobId, empty, null), false, false);
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> doc = Json.parseObject(json);
            return new LoadResult(ReviewStatusCalculator.normalizeDocument(jobId, doc, null), true, false);
        } catch (RuntimeException e) {
            // 损坏文件不覆盖、不阻塞：改名备份，让用户下次保存时重建。
            backupCorrupt(file);
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("schemaVersion", ReviewStatusCalculator.SCHEMA_VERSION);
            empty.put("jobId", jobId);
            empty.put("files", new LinkedHashMap<>());
            return new LoadResult(ReviewStatusCalculator.normalizeDocument(jobId, empty, null), false, true);
        }
    }

    public synchronized Map<String, Object> save(String jobId, Map<String, Object> body) throws IOException {
        requireJobId(jobId);
        Map<String, Object> doc = ReviewStatusCalculator.normalizeDocument(jobId, body, null);
        String json = Json.write(doc);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("审阅文档超过上限 " + MAX_BODY_BYTES + " 字节");
        }
        Files.createDirectories(dir);
        Path target = fileFor(jobId);
        Path tmp = Files.createTempFile(dir, "review-" + jobId + "-", ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return doc;
    }

    public synchronized boolean delete(String jobId) throws IOException {
        requireJobId(jobId);
        return Files.deleteIfExists(fileFor(jobId));
    }

    private void backupCorrupt(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName() + ".bad-" + BAD_TS.format(Instant.now()));
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 备份失败不影响读取降级；后续保存会覆盖。
        }
    }

    private static void requireJobId(String jobId) {
        if (!isValidJobId(jobId)) {
            throw new IllegalArgumentException("非法 jobId（仅允许字母、数字、下划线与短横线）");
        }
    }
}

package com.bempdiff.server;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** M-16：本地审阅目录的保留上限，防止任意 jobId 持续写入把磁盘撑满。 */
    public static final int MAX_REVIEW_FILES = 300;
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
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

    /** 文件存在但无法读取（权限/IO）。与“JSON 损坏”区分，避免把不可读文件静默当成空文档后覆盖。 */
    public static final class UnreadableException extends IOException {
        UnreadableException(String message, Throwable cause) {
            super(message, cause);
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
        } catch (MalformedInputException e) {
            // 字节不是合法 UTF-8：按“内容损坏”备份并降级，用户下次保存可重建。
            backupCorrupt(file);
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("schemaVersion", ReviewStatusCalculator.SCHEMA_VERSION);
            empty.put("jobId", jobId);
            empty.put("files", new LinkedHashMap<>());
            return new LoadResult(ReviewStatusCalculator.normalizeDocument(jobId, empty, null), false, true);
        } catch (IOException e) {
            throw new UnreadableException("审阅文件不可读：" + file, e);
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
        // M-02：审阅人/更新时间以服务端为准，客户端不能通过 body 伪造审计口径。
        doc.put("user", currentUser());
        doc.put("updatedAt", Instant.now().toString());
        String json = Json.write(doc);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("审阅文档超过上限 " + MAX_BODY_BYTES + " 字节");
        }
        Files.createDirectories(dir);
        Path target = fileFor(jobId);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        pruneOldReviews(target, bytes.length);
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

    /** M-16：按「最旧优先」删到文件数与总容量上限内；当前作业的目标文件不删。 */
    private void pruneOldReviews(Path keep, long incomingBytes) throws IOException {
        List<Path> others = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                if (!p.equals(keep)) others.add(p);
            }
        }
        others.sort(Comparator.comparingLong(p -> {
            try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return Long.MAX_VALUE; }
        }));
        long total = incomingBytes;
        for (Path p : others) {
            try { total += Files.size(p); } catch (IOException ignored) { /* 读不到按 0 计 */ }
        }
        int files = others.size() + 1;
        for (Path p : others) {
            if (files <= MAX_REVIEW_FILES && total <= MAX_TOTAL_BYTES) break;
            long sz = 0;
            try { sz = Files.size(p); } catch (IOException ignored) { /* 忽略 */ }
            if (Files.deleteIfExists(p)) {
                files--;
                total = Math.max(0, total - sz);
            }
        }
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

    /** 本地单用户应用的审阅身份：优先显式配置，否则取操作系统账户名，避免客户端任意填写“审阅人”。 */
    private static String currentUser() {
        String u = System.getProperty("bempdiff.reviewUser", "").trim();
        if (u.isEmpty()) u = System.getenv().getOrDefault("BEMPDIFF_REVIEW_USER", "").trim();
        if (u.isEmpty()) u = System.getProperty("user.name", "").trim();
        if (u.isEmpty()) u = "local";
        u = u.replaceAll("[\\r\\n\\t]", " ");
        return u.length() > 120 ? u.substring(0, 120) : u;
    }

    private static void requireJobId(String jobId) {
        if (!isValidJobId(jobId)) {
            throw new IllegalArgumentException("非法 jobId（仅允许字母、数字、下划线与短横线）");
        }
    }
}

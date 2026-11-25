package com.bempdiff.server;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T06218 文件级审阅状态聚合（PRD B1 / §10.2 Review Schema）。
 *
 * <p>职责边界：本类只做「纯计算 + 结构规范化」，不碰磁盘；ReviewStore 负责持久化。
 * 文件状态由 Hunk 状态聚合而来，手动覆盖优先于自动聚合（AC-11）。
 */
public final class ReviewStatusCalculator {

    public static final String SCHEMA_VERSION = "1.0";

    public static final String UNREVIEWED = "unreviewed";
    public static final String IN_PROGRESS = "in_progress";
    public static final String REVIEWED = "reviewed";
    public static final String NEEDS_REVIEW = "needs_review";

    private static final List<String> STATUSES = List.of(UNREVIEWED, IN_PROGRESS, REVIEWED, NEEDS_REVIEW);

    private ReviewStatusCalculator() {
    }

    /** 文档级规范化：补齐 schemaVersion/jobId/user/updatedAt，并逐文件重算 status。 */
    public static Map<String, Object> normalizeDocument(String jobId, Map<String, Object> input, String user) {
        Map<String, Object> src = input == null ? new LinkedHashMap<>() : input;
        Map<String, Object> out = new LinkedHashMap<>(src);
        out.put("schemaVersion", SCHEMA_VERSION);
        out.put("jobId", jobId);
        String u = str(src.get("user"));
        out.put("user", u.isEmpty() ? safeUser(user) : u);
        String updatedAt = str(src.get("updatedAt"));
        out.put("updatedAt", updatedAt.isEmpty() ? now() : updatedAt);

        Object rawFiles = src.get("files");
        Map<String, Object> files = new LinkedHashMap<>();
        if (rawFiles instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.isEmpty()) continue;
                Map<String, Object> file = e.getValue() instanceof Map<?, ?> m ? copyMap(m) : new LinkedHashMap<>();
                files.put(key, normalizeFile(file));
            }
        }
        out.put("files", files);
        // summary 是接口派生字段，不写入 reviews/{jobId}.json，避免同一事实两处存储。
        out.remove("summary");
        out.remove("exists");
        out.remove("ok");
        out.remove("corruptBackup");
        return out;
    }

    /** 单文件规范化：hunks/lines/manualOverride 保留，status 按手动覆盖或 Hunk 聚合重算。 */
    public static Map<String, Object> normalizeFile(Map<String, Object> raw) {
        Map<String, Object> out = raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);

        int hunkTotal = intValue(raw == null ? null : raw.get("hunkTotal"), -1);
        Map<String, Object> hunks = new LinkedHashMap<>();
        Object rawHunks = raw == null ? null : raw.get("hunks");
        if (rawHunks instanceof Map<?, ?> hm) {
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                String id = String.valueOf(e.getKey());
                if (id.isEmpty()) continue;
                hunks.put(id, normalizeStatus(String.valueOf(e.getValue()), UNREVIEWED));
            }
        }
        if (hunkTotal < 0) hunkTotal = hunks.size();
        out.put("hunkTotal", hunkTotal);
        out.put("hunks", hunks);

        boolean manual = boolValue(raw == null ? null : raw.get("manualOverride"));
        // M-02：只有 manualOverride=true 且携带合法 status 才视为已审；缺 status 不能默认 REVIEWED，
        // 否则一个字段缺失的请求就能把文件伪装成“已审”。
        String manualStatus = normalizeStatus(str(raw == null ? null : raw.get("status")), UNREVIEWED);
        List<Object> lines = normalizeLines(raw == null ? null : raw.get("lines"));
        String computed = aggregateFileStatus(hunkTotal, hunks, manual, manualStatus);
        // 旧 localStorage 迁移来的行标记没有 Hunk 总数：不能判「全审」，但显然已开始审。
        if (!manual && hunkTotal <= 0 && !lines.isEmpty() && UNREVIEWED.equals(computed)) {
            computed = IN_PROGRESS;
        }
        out.put("manualOverride", manual);
        out.put("status", computed);
        out.put("lines", lines);
        return out;
    }

    /**
     * 文件状态聚合：
     * - manualOverride=true 时保留手动状态（AC-11，Hunk 未全审也可标已审）；
     * - 任一 Hunk needs_review → needs_review；
     * - 已审 Hunk 覆盖全部 → reviewed；部分 → in_progress；零 → unreviewed；
     * - hunkTotal=0（二进制/无文本差异）不视为「全部已审」，保持 unreviewed，等待人工确认。
     */
    public static String aggregateFileStatus(int hunkTotal, Map<String, ?> hunks,
                                             boolean manualOverride, String manualStatus) {
        if (manualOverride) {
            return normalizeStatus(manualStatus, UNREVIEWED);
        }
        int reviewed = 0;
        boolean needsReview = false;
        if (hunks != null) {
            for (Object v : hunks.values()) {
                String s = normalizeStatus(String.valueOf(v), UNREVIEWED);
                if (REVIEWED.equals(s)) reviewed++;
                else if (NEEDS_REVIEW.equals(s)) needsReview = true;
            }
        }
        if (needsReview) return NEEDS_REVIEW;
        if (hunkTotal <= 0) return UNREVIEWED;
        if (reviewed >= hunkTotal) return REVIEWED;
        if (reviewed > 0) return IN_PROGRESS;
        return UNREVIEWED;
    }

    /** 汇总：四态文件数 + Hunk 进度 + 百分比（1000 文件 O(n)，无正则/无 IO）。 */
    public static Map<String, Object> summarize(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        int totalFiles = 0, reviewedFiles = 0, inProgressFiles = 0, unreviewedFiles = 0, needsReviewFiles = 0, manualFiles = 0;
        long totalHunks = 0, reviewedHunks = 0, needsReviewHunks = 0;
        Object rawFiles = doc == null ? null : doc.get("files");
        if (rawFiles instanceof Map<?, ?> files) {
            for (Object v : files.values()) {
                if (!(v instanceof Map<?, ?> f)) continue;
                totalFiles++;
                String status = normalizeStatus(String.valueOf(f.get("status")), UNREVIEWED);
                switch (status) {
                    case REVIEWED -> reviewedFiles++;
                    case IN_PROGRESS -> inProgressFiles++;
                    case NEEDS_REVIEW -> needsReviewFiles++;
                    default -> unreviewedFiles++;
                }
                boolean manual = boolValue(f.get("manualOverride"));
                if (manual) manualFiles++;
                // M-15：文件级手动“已审”视为覆盖 Hunk 级状态，避免出现“文件已审但汇总仍显示需复核”。
                boolean manualReviewed = manual && REVIEWED.equals(status);

                long hunkTotal = Math.max(0, longValue(f.get("hunkTotal"), 0));
                Object rawHunks = f.get("hunks");
                long reviewedForFile = 0;
                if (rawHunks instanceof Map<?, ?> hm) {
                    for (Object hs : hm.values()) {
                        String s = normalizeStatus(String.valueOf(hs), UNREVIEWED);
                        if (REVIEWED.equals(s)) reviewedForFile++;
                        else if (NEEDS_REVIEW.equals(s) && !manualReviewed) needsReviewHunks++;
                    }
                }
                if (manualReviewed) reviewedForFile = hunkTotal;
                if (reviewedForFile > hunkTotal) hunkTotal = reviewedForFile;
                totalHunks += Math.min(Integer.MAX_VALUE, hunkTotal);
                reviewedHunks += Math.min(Integer.MAX_VALUE, reviewedForFile);
            }
        }
        out.put("totalFiles", totalFiles);
        out.put("reviewedFiles", reviewedFiles);
        out.put("inProgressFiles", inProgressFiles);
        out.put("unreviewedFiles", unreviewedFiles);
        out.put("needsReviewFiles", needsReviewFiles);
        out.put("manualFiles", manualFiles);
        out.put("totalHunks", totalHunks);
        out.put("reviewedHunks", reviewedHunks);
        out.put("needsReviewHunks", needsReviewHunks);
        out.put("filePercent", percent(reviewedFiles, totalFiles));
        out.put("hunkPercent", percent(reviewedHunks, totalHunks));
        return out;
    }

    /** 附加派生 summary 的副本（GET/PUT 响应使用）。 */
    public static Map<String, Object> withSummary(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>(doc == null ? new LinkedHashMap<>() : doc);
        out.put("summary", summarize(doc));
        return out;
    }

    public static boolean isValidStatus(String s) {
        return STATUSES.contains(String.valueOf(s));
    }

    public static String normalizeStatus(String s, String fallback) {
        String v = s == null ? "" : s.trim().toLowerCase();
        if ("unreviewed".equals(v) || "not_reviewed".equals(v)) return UNREVIEWED;
        if ("in_progress".equals(v) || "in-progress".equals(v) || "reviewing".equals(v)) return IN_PROGRESS;
        if ("reviewed".equals(v) || "approved".equals(v) || "done".equals(v)) return REVIEWED;
        if ("needs_review".equals(v) || "needs-review".equals(v) || "needs_recheck".equals(v)) return NEEDS_REVIEW;
        return fallback;
    }

    private static List<Object> normalizeLines(Object raw) {
        List<Object> lines = new ArrayList<>();
        if (!(raw instanceof Iterable<?> it)) return lines;
        Set<Integer> seen = new HashSet<>();
        for (Object v : it) {
            if (v instanceof Number n) {
                int i = n.intValue();
                if (i >= 0 && i <= 2_000_000 && seen.add(i)) lines.add(i);
            }
        }
        return lines;
    }

    private static Map<String, Object> copyMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static String safeUser(String user) {
        String u = user == null ? "" : user.trim();
        if (u.isEmpty()) u = System.getProperty("bempdiff.reviewUser", "");
        if (u.isEmpty()) u = System.getenv().getOrDefault("BEMPDIFF_REVIEW_USER", "");
        if (u.isEmpty()) u = System.getProperty("user.name", "local");
        u = u.replaceAll("[\\r\\n\\t]", " ");
        return u.length() > 120 ? u.substring(0, 120) : u;
    }

    private static int intValue(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static long longValue(Object o, long fallback) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static boolean boolValue(Object o) {
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int percent(long part, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(part * 100.0 / total);
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}

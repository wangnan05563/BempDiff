package com.bempdiff.server;

import com.bempdiff.parse.PackageVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 「关于」页版本更新检查服务（零依赖，仅 JDK 标准库）。
 *
 * <p>数据源：GitHub Releases API {@code GET /repos/{owner}/{repo}/releases/latest}。
 * 比较逻辑复用 {@link PackageVersion#compare}（a 旧于 b 返回负数），保证与包文件版本排序同一口径。
 *
 * <p>安全与配置约定（不得硬编码凭证）：
 * <ul>
 *   <li>强制 HTTPS（api.github.com）；仓库地址不算凭证，可内置默认值，
 *       并允许环境变量 {@code BEMPDIFF_GITHUB_REPO}（格式 {@code owner/repo}）覆盖；</li>
 *   <li>访问令牌从环境变量 {@code GITHUB_TOKEN} 读取（可选）：匿名请求限 60 次/小时/IP，
 *       私有仓库必须提供具备读取权限的 token；token 只在内存中使用，绝不写日志、绝不回显给前端；</li>
 *   <li>请求/响应超时 5s/8s：GitHub 不可达时优雅降级（ok=false + message），不影响其他功能。</li>
 * </ul>
 */
public final class UpdateCheckService {

    private UpdateCheckService() {
    }

    /** 默认仓库（owner/repo）。仓库地址非凭证；用环境变量 BEMPDIFF_GITHUB_REPO 覆盖。 */
    public static final String DEFAULT_REPO = "wangnan05563/BempDiff";

    /** 环境变量名：仓库覆盖（owner/repo）。 */
    public static final String ENV_REPO = "BEMPDIFF_GITHUB_REPO";
    /** 环境变量名：GitHub 访问令牌（可选；私有仓库或提高限流额度时提供）。 */
    public static final String ENV_TOKEN = "GITHUB_TOKEN";

    private static final String API_BASE_DEFAULT = "https://api.github.com/repos/";
    // mutable（volatile）以便契约单测把数据源指向本地假 GitHub（避免依赖外网）；生产恒用上方 HTTPS 默认值
    private static volatile String API_BASE = API_BASE_DEFAULT; // NOSONAR S3008 - 字段名由测试反射读取契约固定（getDeclaredField("API_BASE")），重命名会破坏契约
    private static final String KEY_UP_TO_DATE = "upToDate";
    private static final String KEY_LATEST = "latest";
    private static final String KEY_MESSAGE = "message";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    // ---- 进程内短缓存：避免反复打开「关于」/频繁点检查在 1 小时内打爆 GitHub 匿名限流。
    // 只缓存「最近一次成功拉取的 latest release」，不缓存失败（失败可立即重试）；
    // 键 = repo + token（token 变了视为不同数据源）。TTL 5 分钟。 ----
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static volatile boolean cacheValid;
    private static volatile String cacheKey = "";            // repo + '\u0000' + token
    private static volatile Map<String, Object> cacheLatest; // NOSONAR S3077 - 仅整体引用替换/读取（拉取时新建 map 一次赋值），无内部并发修改，volatile 保证引用可见性即可；null 表示已确认「无 release」（404）
    private static volatile long cacheAtMillis;

    /** 一次缓存命中的拉取结果：latest 为 null 表示无 release（仍算命中，反对重复打 404）。 */
    private static final class LatestResult {
        final Map<String, Object> latest;
        final boolean cached;
        LatestResult(Map<String, Object> latest, boolean cached) {
            this.latest = latest;
            this.cached = cached;
        }
    }

    /** 清空进程内缓存（配置中心保存新 token 后调用，保证下次检查立即走新令牌；亦供测试隔离）。 */
    public static void clearLatestCache() {
        cacheValid = false;
        cacheKey = "";
        cacheLatest = null;
    }

    /**
     * 检查更新入口（令牌回落环境变量 GITHUB_TOKEN）。
     *
     * @param current 当前版本号；允许为空（只查不比）
     */
    public static Map<String, Object> check(String current) {
        return check(current, null);
    }

    /**
     * 检查更新入口（显式令牌）。
     *
     * @param current      当前版本号；允许为空（只查不比）
     * @param configToken  配置中心保存的 GitHub 令牌；为空则回落环境变量 GITHUB_TOKEN
     * @return 统一响应结构（永远 200 语义，由 ok 字段区分成功/失败）：
     *         <pre>{ ok, current, upToDate(Boolean|null), latest:{tag,name,url,publishedAt,body}|null,
     *                repo, message, lastError, cached }</pre>
     */
    public static Map<String, Object> check(String current, String configToken) {
        String repo = repoOf();
        String token = (configToken != null && !configToken.isBlank()) ? configToken : System.getenv(ENV_TOKEN);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("repo", repo);
        resp.put("current", current == null ? "" : current);
        Map<String, Object> latest;
        boolean cached;
        try {
            LatestResult r = latestOf(repo, token);
            latest = r.latest;
            cached = r.cached;
        } catch (Exception e) {
            resp.put("ok", false);
            resp.put(KEY_UP_TO_DATE, null);
            resp.put(KEY_LATEST, null);
            resp.put("cached", false);
            resp.put("lastError", e.getMessage());
            resp.put(KEY_MESSAGE, "检查更新失败：" + friendlyError(e));
            return resp;
        }
        resp.put("cached", cached);
        if (latest == null) {
            resp.put("ok", true);
            resp.put(KEY_UP_TO_DATE, null);
            resp.put(KEY_LATEST, null);
            resp.put(KEY_MESSAGE, "仓库还没有发布任何 Release（请在 GitHub 上创建 Release 后再检查）");
            return resp;
        }
        String tag = String.valueOf(latest.getOrDefault("tag", ""));
        boolean hasCurrent = current != null && !current.isBlank();
        Boolean upToDate = null;
        // 只有 tag 本身是版本号时才比较：滚动固定 tag（如 "bempDiff"）不含数字，
        // 与 PackageVersion.compare 硬比会得出「当前更旧」，从而对最新版安装包误报「发现新版本」。
        boolean comparable = hasCurrent && stripVPrefix(tag).matches(".*\\d.*");
        if (comparable) {
            // strip 前导 v/V：tag 常见 v1.2.3 形态，与纯数字版本（0.1.2026083102）对齐后比较
            int cmp = PackageVersion.compare(stripVPrefix(current), stripVPrefix(tag));
            upToDate = cmp >= 0; // 当前 >= 最新 → 已是最新
        }
        resp.put("ok", true);
        resp.put(KEY_UP_TO_DATE, upToDate);
        resp.put(KEY_LATEST, latest);
        if (upToDate == null) {
            resp.put(KEY_MESSAGE, hasCurrent
                    ? "已获取最新版本 " + tag + "（该 Release 标签不是版本号，无法与当前版本 " + current + " 比较）"
                    : "已获取最新版本 " + tag + "（当前版本未知，未比较）");
        } else if (upToDate.booleanValue()) {
            resp.put(KEY_MESSAGE, "当前已是最新版本");
        } else {
            resp.put(KEY_MESSAGE, "发现新版本 " + tag + "，请前往 Release 页下载安装包");
        }
        return resp;
    }

    /** 解析生效仓库：环境变量 BEMPDIFF_GITHUB_REPO（owner/repo 格式合法时）优先，否则内置默认。 */
    public static String repoOf() {
        String env = System.getenv(ENV_REPO);
        if (env != null && env.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return env;
        }
        return DEFAULT_REPO;
    }

    /** 带进程内缓存的 latest 拉取：TTL 内同 repo+token 命中直接返回上次结果，避免重复打 GitHub API。 */
    private static LatestResult latestOf(String repo, String token) throws IOException {
        String key = repo + '\u0000' + (token == null ? "" : token);
        long now = System.currentTimeMillis();
        if (cacheValid && key.equals(cacheKey) && (now - cacheAtMillis) < CACHE_TTL_MS) {
            return new LatestResult(cacheLatest, true);
        }
        // 失败不缓存：让用户改完令牌/网络后可立即重试；异常向上抛由 check() 统一转为 ok=false。
        Map<String, Object> latest = fetchLatestRelease(repo, token).orElse(null);
        cacheLatest = latest;
        cacheKey = key;
        cacheAtMillis = now;
        cacheValid = true;
        return new LatestResult(latest, false);
    }

    /**
     * 调 GitHub Releases API 拉取最新 release（令牌回落环境变量 GITHUB_TOKEN）。
     */
    static Map<String, Object> fetchLatestRelease(String repo) throws IOException {
        return fetchLatestRelease(repo, System.getenv(ENV_TOKEN)).orElse(null);
    }

    /**
     * 调 GitHub Releases API 拉取最新 release。
     *
     * @param token 显式令牌；为空则回落环境变量 GITHUB_TOKEN
     * @return 含 release 字段 map（tag/name/url/publishedAt/body）的 Optional；
     *         仓库无任何 release（HTTP 404）返回 Optional.empty()，交由上层提示
     * @throws IOException 网络异常；非 2xx 非 404 响应（含限流 403、认证失败 401）抛 IllegalStateException
     */
    static Optional<Map<String, Object>> fetchLatestRelease(String repo, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(API_BASE + repo + "/releases/latest")
                .toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        // GitHub API 必须携带 User-Agent，否则 403
        conn.setRequestProperty("User-Agent", "BempDiff-UpdateCheck");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        int code = conn.getResponseCode();
        if (code == 404) {
            return Optional.empty(); // 尚无任何 release：不算错误，交由上层提示
        }
        if (code < 200 || code >= 300) {
            String detail = readErrDetail(conn);
            throw new IllegalStateException("HTTP " + code + (detail.isEmpty() ? "" : (" - " + detail)));
        }
        String body;
        try (InputStream in = conn.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Object> json = Json.parseObject(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tag", Json.str(json, "tag_name", ""));
        out.put("name", Json.str(json, "name", ""));
        out.put("url", Json.str(json, "html_url", ""));
        out.put("publishedAt", Json.str(json, "published_at", ""));
        out.put("body", Json.str(json, "body", ""));
        // 解析 Windows 安装包资产（.exe，跳过校验和 .blockmap / 旧版等），供前端「立即更新」一键下载。
        // 优先 .setup.exe / .exe 的 browser_download_url；找不到则置空（前端据此隐藏一键更新，仅保留跳转下载页）。
        out.put("downloadUrl", firstExeDownloadUrl(json));
        return Optional.of(out);
    }

    /** 从 release JSON 的 assets 中挑取第一个 Windows 安装包（.exe）的 browser_download_url；无则返回空串。 */
    private static String firstExeDownloadUrl(Map<String, Object> json) {
        Object a = json.get("assets");
        if (!(a instanceof java.util.List)) return "";
        for (Object item : (java.util.List<?>) a) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            String name = String.valueOf(m.get("name"));
            String dl = String.valueOf(m.get("browser_download_url"));
            if (!"null".equals(name) && name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")
                    && dl != null && !"null".equals(dl) && dl.startsWith("https://")) {
                return dl;
            }
        }
        return "";
    }

    /** 读取错误响应体里的 message 字段（GitHub 错误 JSON 形如 {"message":"..."}），失败返回空串。 */
    private static String readErrDetail(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream()) {
            if (in == null) return "";
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> m = Json.parseObject(s);
            return Json.str(m, KEY_MESSAGE, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 把底层异常翻译成用户可读的排查提示（限流/令牌/网络）。 */
    static String friendlyError(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.contains("HTTP 403") || msg.contains("HTTP 429")) {
            return msg + " —— 多为 GitHub API 限流（匿名 60 次/小时/IP）。"
                    + "可设置环境变量 GITHUB_TOKEN 提升额度，稍后重试。";
        }
        if (msg.contains("HTTP 401")) {
            return msg + " —— GITHUB_TOKEN 无效或权限不足，请核对该令牌（私有仓库需具备读取权限）。";
        }
        if (msg.contains("HTTP 404")) {
            return msg + " —— 仓库不存在或为私有仓库且未提供有效 GITHUB_TOKEN。"
                    + "可用环境变量 BEMPDIFF_GITHUB_REPO 覆盖仓库地址。";
        }
        return msg + " —— 请检查本机网络/代理是否可达 api.github.com。";
    }

    /** 去掉版本串前导 v/V（v1.2.3 → 1.2.3），便于与纯数字版本比较。 */
    static String stripVPrefix(String v) {
        if (v == null) return "";
        return (v.startsWith("v") || v.startsWith("V")) ? v.substring(1) : v;
    }
}

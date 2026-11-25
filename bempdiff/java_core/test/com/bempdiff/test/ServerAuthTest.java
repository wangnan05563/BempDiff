package com.bempdiff.test;

import com.bempdiff.server.ServerConfig;
import com.bempdiff.server.Json;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.bempdiff.test.ServerTestHarness.launch;
import static com.bempdiff.test.ServerTestHarness.newConfig;

/**
 * H-02 本机会话认证：所有 /api/* 需要随机会话 token；浏览器首页下发 HttpOnly+SameSite=Strict Cookie；
 * 跨站 Origin / Sec-Fetch-Site: cross-site 拒绝；固定 CSRF 头仍独立校验。
 */
public final class ServerAuthTest {

    private static ServerTestHarness.Server start(Path tmp) throws Exception {
        ServerConfig config = newConfig(tmp);
        Path webroot = Files.createDirectories(tmp.resolve("webroot"));
        Files.writeString(webroot.resolve("index.html"), "<!doctype html><title>t</title>", StandardCharsets.UTF_8);
        return launch(config, webroot);
    }

    public void testApiRequiresSessionToken() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-auth-token");
        try {
            ServerTestHarness.Server s = start(tmp);
            Asserts.assertEquals("无 token 读取应 401", 401,
                    s.requestNoToken("GET", "/api/config", null).statusCode());
            Asserts.assertEquals("错误 token 应 401", 401,
                    s.requestWithToken("GET", "/api/config", "wrong-token").statusCode());
            Asserts.assertEquals("正确 token 应 200", 200, s.get("/api/config").statusCode());
            Asserts.assertEquals("OPTIONS 不应被 token 拦截", 204,
                    s.requestNoToken("OPTIONS", "/api/session/preflight", null).statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    public void testCrossSiteOriginRejectedEvenWithValidToken() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-auth-origin");
        try {
            ServerTestHarness.Server s = start(tmp);
            Asserts.assertEquals("恶意 Origin 应 403", 403,
                    s.requestWithOrigin("GET", "/api/config", "https://evil.example", null).statusCode());
            Asserts.assertEquals("同机非白名单端口 Origin 应 403", 403,
                    s.requestWithOrigin("GET", "/api/config", "http://127.0.0.1:9999", "same-origin").statusCode());
            Asserts.assertEquals("本进程端口 Origin 应 200", 200,
                    s.requestWithOrigin("GET", "/api/config", "http://127.0.0.1:" + s.port, "same-origin").statusCode());
            Asserts.assertEquals("cross-site 应 403", 403,
                    s.requestWithOrigin("GET", "/api/config", "http://127.0.0.1:5180", "cross-site").statusCode());
            Asserts.assertEquals("loopback 同源应 200", 200,
                    s.requestWithOrigin("GET", "/api/config", "http://127.0.0.1:5180", "same-site").statusCode());
            Asserts.assertEquals("无 Origin 的原生客户端应 200", 200, s.get("/api/config").statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    public void testIndexCookieAuthorizesApi() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-auth-cookie");
        try {
            ServerTestHarness.Server s = start(tmp);
            HttpResponse<String> index = s.requestNoToken("GET", "/", null);
            Asserts.assertEquals("首页无 token 也应可加载", 200, index.statusCode());
            List<String> cookies = index.headers().allValues("Set-Cookie");
            Asserts.assertTrue("首页应下发 Set-Cookie", !cookies.isEmpty());
            String raw = cookies.get(0);
            Asserts.assertContains("Cookie 应 HttpOnly", raw, "HttpOnly");
            Asserts.assertContains("Cookie 应 SameSite=Strict", raw, "SameSite=Strict");
            Asserts.assertContains("Cookie 名", raw, "BEMPDIFF_SESSION=");
            String cookiePair = raw.split(";", 2)[0];
            Asserts.assertEquals("仅 Cookie 应可读 API", 200,
                    s.requestWithCookie("GET", "/api/config", cookiePair).statusCode());
            // GET 直链查询参数兜底（下载/诊断包在开发态无 Cookie 时使用）
            Asserts.assertEquals("GET token 查询参数应可读", 200,
                    s.requestNoToken("GET", "/api/config?token=" + ServerTestHarness.enc(s.token), null).statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    public void testMissingGuardStillRejected() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-auth-guard");
        try {
            ServerTestHarness.Server s = start(tmp);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("files", new java.util.LinkedHashMap<String, Object>());
            Asserts.assertEquals("有效 token 但无固定头，变更请求应 403", 403,
                    s.requestNoGuard("PUT", "/api/review/job-1", Json.write(body)).statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }
}

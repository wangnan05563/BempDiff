package com.bempdiff.test;

import com.bempdiff.server.ServerConfig;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bempdiff.test.ServerTestHarness.json;
import static com.bempdiff.test.ServerTestHarness.launch;
import static com.bempdiff.test.ServerTestHarness.newConfig;

/**
 * BempServer 静态资源托管 + 配置读写（/api/config）与 CORS/OPTIONS/方法限制的集成测试。
 * 覆盖 handleStatic / handleConfig / handleRunningTasks / handleExportApi(list) 及 OPTIONS 预检分支。
 */
public final class ServerStaticAndConfigTest {

    /** webroot 下放置 index.html：GET / 与 /index.html 都应命中真实静态文件。 */
    public void testStaticServesWebrootIndexHtml() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-static");
        try {
            Path webroot = Files.createDirectories(tmp.resolve("webroot"));
            Files.writeString(webroot.resolve("index.html"),
                    "<!doctype html><html><body>MARKER_STATIC_INDEX_9f3a</body></html>", StandardCharsets.UTF_8);
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, webroot);

            HttpResponse<String> root = s.get("/");
            Asserts.assertEquals("根路径应 200", 200, root.statusCode());
            Asserts.assertContains("根路径应回 index.html 内容", root.body(), "MARKER_STATIC_INDEX_9f3a");
            Asserts.assertContains("应带 text/html", root.headers().firstValue("Content-Type").orElse(""), "text/html");

            HttpResponse<String> idx = s.get("/index.html");
            Asserts.assertEquals("/index.html 应 200", 200, idx.statusCode());
            Asserts.assertContains("/index.html 内容一致", idx.body(), "MARKER_STATIC_INDEX_9f3a");
        } finally {
            deleteTree(tmp);
        }
    }

    /** 静态资源缺失时不应 404 裸奔，而是回落到内置占位首页（服务存活提示）。 */
    public void testStaticMissingFallsBackToPlaceholder() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-static2");
        try {
            Path webroot = Files.createDirectories(tmp.resolve("webroot")); // 空 webroot，无 index.html
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, webroot);

            HttpResponse<String> missing = s.get("/assets/does-not-exist.css");
            Asserts.assertEquals("缺失静态应回占位页(200)", 200, missing.statusCode());
            Asserts.assertContains("占位页含服务标题", missing.body(), "BempDiff Web");
            Asserts.assertContains("占位页列出 API 端点", missing.body(), "/api/session/compare");
        } finally {
            deleteTree(tmp);
        }
    }

    /** 二次请求同一路径命中 P0-C 静态缓存（mtime+size 未变则复用内存字节），内容保持稳定。 */
    public void testStaticCacheReturnsStableBytes() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-static3");
        try {
            Path webroot = Files.createDirectories(tmp.resolve("webroot"));
            Files.writeString(webroot.resolve("app.js"), "var x=1;CACHE_ME", StandardCharsets.UTF_8);
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, webroot);

            HttpResponse<String> a = s.get("/app.js");
            HttpResponse<String> b = s.get("/app.js");
            Asserts.assertEquals("首次 200", 200, a.statusCode());
            Asserts.assertEquals("二次 200", 200, b.statusCode());
            Asserts.assertEquals("两次内容一致", a.body(), b.body());
            Asserts.assertContains("内容命中缓存字节", b.body(), "CACHE_ME");
            Asserts.assertContains("js 应带正确 mime", a.headers().firstValue("Content-Type").orElse(""), "javascript");
        } finally {
            deleteTree(tmp);
        }
    }

    /** GET /api/config 应返回服务端默认配置的 JSON（含 AI 相关字段）。 */
    public void testConfigGetReturnsDefaults() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-cfg");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> r = s.get("/api/config");
            Asserts.assertEquals("config GET 应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("默认 aiProvider=openai", "openai", j.get("aiProvider"));
            Asserts.assertEquals("默认 aiEnabled=false", Boolean.FALSE, j.get("aiEnabled"));
            Asserts.assertTrue("应含 aiBaseUrl", j.containsKey("aiBaseUrl"));
            Asserts.assertTrue("应含 unpackNested 键", j.containsKey("unpackNested"));
            // 默认未持久化 key：仅给 hasApiKey 标记，不回显明文
            Asserts.assertEquals("默认无明文 apiKey", false, j.containsKey("aiApiKey"));
            Asserts.assertEquals("hasApiKey 应为 false", Boolean.FALSE, j.get("hasApiKey"));
        } finally {
            deleteTree(tmp);
        }
    }

    /** PUT /api/config 写入后 GET 应回显更新值；并落盘持久化（跨实例恢复）。 */
    public void testConfigPutRoundTripAndPersist() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-cfg2");
        try {
            Path cfgFile = tmp.resolve("c.properties");
            ServerConfig config = new ServerConfig(cfgFile);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            Map<String, Object> put = new LinkedHashMap<>();
            put.put("aiModel", "qwen-max-put");
            put.put("stageATopK", 7);
            HttpResponse<String> r1 = s.put("/api/config", com.bempdiff.server.Json.write(put));
            Asserts.assertEquals("config PUT 应 200", 200, r1.statusCode());
            Asserts.assertEquals("PUT 回显新 model", "qwen-max-put", json(r1).get("aiModel"));

            HttpResponse<String> r2 = s.get("/api/config");
            Asserts.assertEquals("GET 反映更新后的 model", "qwen-max-put", json(r2).get("aiModel"));
            Asserts.assertEquals("GET 反映更新后的 stageATopK", 7L, ((Number) json(r2).get("stageATopK")).longValue());

            // 异步落盘：轮询等待磁盘写入后新建实例读回（updateFromAsync 会合并写）
            String fromDisk = null;
            for (int i = 0; i < 60; i++) {
                ServerConfig reloaded = new ServerConfig(cfgFile);
                Object m = reloaded.toJson().get("aiModel");
                if ("qwen-max-put".equals(m)) { fromDisk = String.valueOf(m); break; }
                Thread.sleep(50);
            }
            Asserts.assertEquals("落盘后重启应恢复 aiModel", "qwen-max-put", fromDisk);
        } finally {
            deleteTree(tmp);
        }
    }

    /** OPTIONS 预检（多端点）应返回 204 且不返回 CORS 许可；非法方法（config DELETE）应 405。 */
    public void testOptionsPreflightAndUnsupportedMethod() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-cors");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> optCfg = s.options("/api/config");
            Asserts.assertEquals("OPTIONS /api/config 应 204", 204, optCfg.statusCode());
            Asserts.assertFalse("不应返回通配 ACAO", optCfg.headers().firstValue("Access-Control-Allow-Origin").isPresent());

            HttpResponse<String> optCmp = s.options("/api/session/compare");
            Asserts.assertEquals("OPTIONS compare 应 204", 204, optCmp.statusCode());

            HttpResponse<String> optRunning = s.options("/api/tasks/running");
            Asserts.assertEquals("OPTIONS running 应 204", 204, optRunning.statusCode());

            HttpResponse<String> bad = s.del("/api/config");
            Asserts.assertEquals("config DELETE 应 405", 405, bad.statusCode());
            Asserts.assertTrue("405 应带 error 字段", json(bad).containsKey("error"));
        } finally {
            deleteTree(tmp);
        }
    }

    /** GET /api/tasks/running 应返回 {running:[...]}（无进行中任务时为空数组）。 */
    public void testRunningTasksEmpty() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-run");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> r = s.get("/api/tasks/running");
            Asserts.assertEquals("running GET 应 200", 200, r.statusCode());
            Object running = json(r).get("running");
            Asserts.assertTrue("running 应为 List", running instanceof List);
            Asserts.assertTrue("初始应无进行中任务", ((List<?>) running).isEmpty());

            HttpResponse<String> bad = s.post("/api/tasks/running", "{}");
            Asserts.assertEquals("running POST 应 405", 405, bad.statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    /** GET /api/export（list 分支）应返回导出记录数组（初始为空）。 */
    public void testExportListInitialEmpty() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-exp");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> r = s.get("/api/export");
            Asserts.assertEquals("export list 应 200", 200, r.statusCode());
            // 顶层是 JSON 数组
            Asserts.assertTrue("应返回数组体", r.body().trim().startsWith("["));
            Asserts.assertContains("空数组", r.body().trim(), "[");
        } finally {
            deleteTree(tmp);
        }
    }

    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> st = Files.walk(dir)) {
            List<Path> ps = new ArrayList<>();
            st.sorted(java.util.Comparator.reverseOrder()).forEach(ps::add);
            for (Path p : ps) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { /* 尽力清理 */ }
            }
        } catch (Exception ignored) { /* 忽略 */ }
    }
}

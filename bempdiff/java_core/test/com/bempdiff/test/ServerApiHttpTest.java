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
 * BempServer API 面的集成测试：错误分支、方法限制、路由分派、AI 端点（本地桩 + 不可达降级）与手动清理。
 * 覆盖 handleAiTest / handleAiModels / handleAiContext / handleCleanupTemp / handleEntry(错误路径) /
 * handleJob(错误路径) / handleFile(错误路径) / handleCompare(参数与非法 JSON)。
 *
 * <p>零外网：AI 走本地 com.sun.net.httpserver 桩；不可达用例指向刚关闭的空闲端口（连接被拒），
 * 均为 127.0.0.1，验证 AI 失败路径返回「错误 JSON + HTTP 200」而非 500 裸奔。
 */
public final class ServerApiHttpTest {

    // ---------------- AI 端点 ----------------

    /**
     * 造一个已把 aiBaseUrl 配到目标地址的 ServerConfig。
     * 之所以在配置里预置 baseUrl（而非仅靠 POST body）：handleAiTest/handleAiModels 读取 body 覆写时
     * 依赖 {@code getRequestBody().available()>0}，该值在体到达竞态下可能为 0 → 回落到默认
     * api.openai.com 并触发 15s 外网超时（破坏零外网红线）。预置本地 baseUrl 后，覆写命中与否都指向
     * 本机地址，既确定又不打外网；同时仍发 body 以覆盖 readAiTestConfig 的覆写分支。
     */
    private static ServerConfig configWithBaseUrl(Path tempRoot, String aiBaseUrl) throws Exception {
        ServerConfig config = newConfig(tempRoot);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiBaseUrl", aiBaseUrl);
        config.updateFrom(m); // 同步落盘，确保 toAiConfig 读到
        return config;
    }

    /** /api/ai/test 指向本地桩（GET /v1/models 返回 200）：应判定连接成功。 */
    public void testAiTestAgainstLocalStubSucceeds() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-aite");
        com.sun.net.httpserver.HttpServer stub = null;
        try {
            stub = ServerTestHarness.startAiStub("{\"data\":[{\"id\":\"stub-model\"}]}");
            int stubPort = stub.getAddress().getPort();
            String stubBase = "http://127.0.0.1:" + stubPort + "/v1";
            ServerConfig config = configWithBaseUrl(tmp, stubBase);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("baseUrl", stubBase);
            HttpResponse<String> r = s.post("/api/ai/test", com.bempdiff.server.Json.write(body));

            Asserts.assertEquals("ai/test 应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("本地桩连接应 ok=true", Boolean.TRUE, j.get("ok"));
            Asserts.assertEquals("成功应给中文提示", "连接成功", j.get("message"));
        } finally {
            if (stub != null) stub.stop(0);
            deleteTree(tmp);
        }
    }

    /** /api/ai/models 指向本地桩：应解析出 data[].id 模型列表。 */
    public void testAiModelsAgainstLocalStubReturnsList() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-aimd");
        com.sun.net.httpserver.HttpServer stub = null;
        try {
            stub = ServerTestHarness.startAiStub("{\"data\":[{\"id\":\"alpha\"},{\"id\":\"beta\"}]}");
            int stubPort = stub.getAddress().getPort();
            String stubBase = "http://127.0.0.1:" + stubPort + "/v1";
            ServerConfig config = configWithBaseUrl(tmp, stubBase);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("baseUrl", stubBase);
            HttpResponse<String> r = s.post("/api/ai/models", com.bempdiff.server.Json.write(body));

            Asserts.assertEquals("ai/models 应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("应 ok=true（lastError=" + j.get("lastError") + " msg=" + j.get("message") + "）",
                    Boolean.TRUE, j.get("ok"));
            Object models = j.get("models");
            Asserts.assertTrue("models 应为 List", models instanceof List);
            List<?> ml = (List<?>) models;
            Asserts.assertTrue("应含 alpha", ml.contains("alpha"));
            Asserts.assertTrue("应含 beta", ml.contains("beta"));
            Asserts.assertContains("应提示已获取数量", String.valueOf(j.get("message")), "2");
        } finally {
            if (stub != null) stub.stop(0);
            deleteTree(tmp);
        }
    }

    /** /api/ai/test 指向不可达（刚关闭的空闲端口）：应优雅降级为 200 + ok=false，而非 500。 */
    public void testAiTestUnreachableDegradesGracefully() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-aitfail");
        try {
            int deadPort = ServerTestHarness.freePort(); // 探测后立即关闭 → 无监听 → 连接被拒
            ServerConfig config = configWithBaseUrl(tmp, "http://127.0.0.1:" + deadPort + "/v1");
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("baseUrl", "http://127.0.0.1:" + deadPort + "/v1");
            HttpResponse<String> r = s.post("/api/ai/test", com.bempdiff.server.Json.write(body));

            Asserts.assertEquals("不可达也应 200（非 500 裸奔）", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("应 ok=false", Boolean.FALSE, j.get("ok"));
            Asserts.assertTrue("应带 message", j.containsKey("message"));
            Asserts.assertTrue("应带 lastError", j.containsKey("lastError"));
        } finally {
            deleteTree(tmp);
        }
    }

    /** /api/ai/models 指向不可达：应 200 + ok=false + 空 models。 */
    public void testAiModelsUnreachableReturnsEmpty() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-aimdfail");
        try {
            int deadPort = ServerTestHarness.freePort();
            ServerConfig config = configWithBaseUrl(tmp, "http://127.0.0.1:" + deadPort + "/v1");
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("baseUrl", "http://127.0.0.1:" + deadPort + "/v1");
            HttpResponse<String> r = s.post("/api/ai/models", com.bempdiff.server.Json.write(body));

            Asserts.assertEquals("不可达 models 应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("应 ok=false", Boolean.FALSE, j.get("ok"));
            Asserts.assertTrue("models 应为空数组", j.get("models") instanceof List && ((List<?>) j.get("models")).isEmpty());
        } finally {
            deleteTree(tmp);
        }
    }

    /** /api/ai/context 未指定目录且配置未设项目上下文目录：应 200 + ok=false 提示未指定。 */
    public void testAiContextWithoutDir() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-aictx");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> r = s.get("/api/ai/context");
            Asserts.assertEquals("ai/context 无目录应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("应 ok=false", Boolean.FALSE, j.get("ok"));
            Asserts.assertTrue("应带 message", j.get("message") != null);
        } finally {
            deleteTree(tmp);
        }
    }

    // ---------------- 手动清理 ----------------

    /** POST /api/admin/cleanup 应返回 200 + ok=true 及 freedBytes/files/dirs 计数（只校验形状，计数依赖环境不定）。 */
    public void testAdminCleanupReturnsShape() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-clean");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> opt = s.options("/api/admin/cleanup");
            Asserts.assertEquals("cleanup OPTIONS 应 204", 204, opt.statusCode());

            HttpResponse<String> r = s.post("/api/admin/cleanup", "{}");
            Asserts.assertEquals("cleanup 应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("应 ok=true", Boolean.TRUE, j.get("ok"));
            Asserts.assertTrue("应含 freedBytes", j.containsKey("freedBytes"));
            Asserts.assertTrue("应含 files", j.containsKey("files"));
            Asserts.assertTrue("应含 dirs", j.containsKey("dirs"));
        } finally {
            deleteTree(tmp);
        }
    }

    // ---------------- FR-011 诊断包 ----------------

    /** GET /api/admin/diag-bundle 应 200 + zip 魔数（PK）+ attachment 头；POST 应 405。 */
    public void testDiagBundleDownloadsZip() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-diag");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<byte[]> r = s.getRaw("/api/admin/diag-bundle");
            Asserts.assertEquals("诊断包应 200", 200, r.statusCode());
            Asserts.assertTrue("应含 attachment 下发头", r.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
            byte[] b = r.body();
            Asserts.assertTrue("响应体应非空", b.length > 0);
            Asserts.assertTrue("应为 zip 魔数（PK）", b.length > 2 && b[0] == 'P' && b[1] == 'K');
            Asserts.assertTrue("验收②：包体应 <5MB", b.length < 5 * 1024 * 1024);
            // env.json 内容断言在 DiagBundleTest（zip 条目 deflate 压缩后无法按明文搜特征，
            // 此处仅验证端到端 zip 通道；内容正确性由构建器单测覆盖）

            HttpResponse<String> post = s.post("/api/admin/diag-bundle", "{}");
            Asserts.assertEquals("非 GET 应 405", 405, post.statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    // ---------------- 错误分支 / 方法限制 / 路由 ----------------

    /** /api/job 未知 jobId → 404；缺少 jobId → 400。 */
    public void testJobErrorPaths() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-joberr");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> unknown = s.get("/api/job/does-not-exist/status");
            Asserts.assertEquals("未知 job 应 404", 404, unknown.statusCode());
            Asserts.assertContains("404 应含任务不存在", String.valueOf(json(unknown).get("error")), "does-not-exist");

            HttpResponse<String> missing = s.get("/api/job/");
            Asserts.assertEquals("缺 jobId 应 400", 400, missing.statusCode());
            Asserts.assertContains("应提示缺 jobId", String.valueOf(json(missing).get("error")), "jobId");
        } finally {
            deleteTree(tmp);
        }
    }

    /** /api/entry 路由与参数校验：未知子操作 404、缺参 400、未知 job 404。 */
    public void testEntryErrorPaths() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-entryerr");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> badSub = s.get("/api/entry/bogus");
            Asserts.assertEquals("未知 entry 操作应 404", 404, badSub.statusCode());
            Asserts.assertContains("应含未知 entry 操作", String.valueOf(json(badSub).get("error")), "未知 entry 操作");

            HttpResponse<String> missing = s.get("/api/entry/decompile");
            Asserts.assertEquals("decompile 缺参应 400", 400, missing.statusCode());
            Asserts.assertContains("应含缺参提示", String.valueOf(json(missing).get("error")), "缺少 jobId");

            HttpResponse<String> unknownJob = s.get("/api/entry/decompile?jobId=nope&key=a");
            Asserts.assertEquals("decompile 未知 job 应 404", 404, unknownJob.statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    /** /api/session/compare 参数校验与非法 JSON：缺路径 400，非法 body 500（错误 JSON，非裸奔）。 */
    public void testCompareParamAndBadJson() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-cmperr");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> missing = s.post("/api/session/compare", "{}");
            Asserts.assertEquals("缺路径应 400", 400, missing.statusCode());
            Asserts.assertContains("应提示 leftPath", String.valueOf(json(missing).get("error")), "leftPath");

            HttpResponse<String> badJson = s.post("/api/session/compare", "{not valid json");
            Asserts.assertEquals("非法 JSON 应 500（带 error JSON）", 500, badJson.statusCode());
            Asserts.assertTrue("应带 error 字段", json(badJson).containsKey("error"));
        } finally {
            deleteTree(tmp);
        }
    }

    /** /api/file 方法与参数校验：非 POST 405、缺参 400、未知 job 404。 */
    public void testFileMethodAndParamErrors() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-fileerr");
        try {
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            HttpResponse<String> get = s.get("/api/file");
            Asserts.assertEquals("GET /api/file 应 405", 405, get.statusCode());

            HttpResponse<String> opt = s.options("/api/file");
            Asserts.assertEquals("OPTIONS /api/file 应 204", 204, opt.statusCode());

            HttpResponse<String> missing = s.post("/api/file", "{}");
            Asserts.assertEquals("缺参应 400", 400, missing.statusCode());
            Asserts.assertContains("应含缺参提示", String.valueOf(json(missing).get("error")), "缺少 jobId");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", "nope");
            body.put("key", "a.txt");
            body.put("op", "info");
            HttpResponse<String> unknown = s.post("/api/file", com.bempdiff.server.Json.write(body));
            Asserts.assertEquals("未知 job 应 404", 404, unknown.statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    /** T06218 /api/review：PUT 聚合/落盘、GET 回读、非法 jobId 400、方法 405、DELETE 清理。 */
    public void testReviewApiRoundTripAndGuards() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-review-api");
        String oldDir = System.getProperty("bempdiff.reviewDir");
        try {
            Path reviews = tmp.resolve("reviews");
            System.setProperty("bempdiff.reviewDir", reviews.toString());
            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            Map<String, Object> file = new LinkedHashMap<>();
            file.put("hunkTotal", 2);
            Map<String, Object> hunks = new LinkedHashMap<>();
            hunks.put("0", "reviewed");
            hunks.put("1", "unreviewed");
            file.put("hunks", hunks);
            file.put("lines", List.of(1));
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("a/Foo.class", file);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("files", files);
            body.put("jobId", "spoofed-should-be-overridden");

            HttpResponse<String> put = s.put("/api/review/job-api-test", com.bempdiff.server.Json.write(body));
            Asserts.assertEquals("PUT 应 200", 200, put.statusCode());
            Map<String, Object> pj = json(put);
            Asserts.assertEquals("路径 jobId 权威", "job-api-test", pj.get("jobId"));
            Asserts.assertEquals("exists", Boolean.TRUE, pj.get("exists"));
            Map<?, ?> sum = (Map<?, ?>) pj.get("summary");
            Asserts.assertEquals("inProgressFiles", 1, ((Number) sum.get("inProgressFiles")).intValue());
            Asserts.assertEquals("totalHunks", 2, ((Number) sum.get("totalHunks")).intValue());
            Asserts.assertTrue("应落盘到 reviewDir", Files.isRegularFile(reviews.resolve("job-api-test.json")));

            HttpResponse<String> get = s.get("/api/review/job-api-test");
            Asserts.assertEquals("GET 应 200", 200, get.statusCode());
            Map<String, Object> gj = json(get);
            Asserts.assertEquals("GET exists", Boolean.TRUE, gj.get("exists"));
            Map<?, ?> gf = (Map<?, ?>) ((Map<?, ?>) gj.get("files")).get("a/Foo.class");
            Asserts.assertEquals("部分 Hunk → in_progress", "in_progress", gf.get("status"));

            HttpResponse<String> bad = s.get("/api/review/%2e%2e%2fescape");
            Asserts.assertEquals("非法 jobId 应 400", 400, bad.statusCode());

            HttpResponse<String> method = s.post("/api/review/job-api-test", "{}");
            Asserts.assertEquals("POST 应 405", 405, method.statusCode());
            Asserts.assertEquals("OPTIONS 应 204", 204, s.options("/api/review/job-api-test").statusCode());

            HttpResponse<String> del = s.del("/api/review/job-api-test");
            Asserts.assertEquals("DELETE 应 200", 200, del.statusCode());
            Asserts.assertFalse("文件应删除", Files.exists(reviews.resolve("job-api-test.json")));
            Asserts.assertEquals("删后 GET exists=false", Boolean.FALSE, json(s.get("/api/review/job-api-test")).get("exists"));
        } finally {
            if (oldDir == null) System.clearProperty("bempdiff.reviewDir");
            else System.setProperty("bempdiff.reviewDir", oldDir);
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

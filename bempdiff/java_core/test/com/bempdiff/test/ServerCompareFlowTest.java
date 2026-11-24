package com.bempdiff.test;

import com.bempdiff.server.ServerConfig;

import java.net.URLEncoder;
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
 * BempServer 真实比对流水线集成测试：提交比对 → 轮询 job → 取差异树/统计 → 单文件内容(entry) →
 * 导出打包(export) → Markdown 报告；并覆盖 folder 模式的 /api/file、job cancel、未知 job 操作。
 *
 * <p>用 TestFixtures 造两个同名不同版本的小包（可被 PackageVersion 识别以触发智能排序），
 * 差异文件均为纯文本（不含 .class），使报告/统计路径不依赖反编译器，快速且确定。
 */
public final class ServerCompareFlowTest {

    /** 提交包比对并轮询至 DONE，返回 status 响应 Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> submitAndWaitDone(ServerTestHarness.Server s, String body) throws Exception {
        HttpResponse<String> submit = s.post("/api/session/compare", body);
        Asserts.assertEquals("compare 提交应 200", 200, submit.statusCode());
        Map<String, Object> sub = json(submit);
        String jobId = String.valueOf(sub.get("jobId"));
        Asserts.assertTrue("应返回 jobId", jobId.startsWith("job-"));

        Map<String, Object> st = null;
        String last = "";
        for (int i = 0; i < 400; i++) { // 最多约 20s
            HttpResponse<String> rs = s.get("/api/job/" + jobId + "/status");
            Asserts.assertEquals("status 查询应 200", 200, rs.statusCode());
            st = json(rs);
            last = String.valueOf(st.get("status"));
            if ("DONE".equals(last) || "ERROR".equals(last) || "CANCELLED".equals(last)) break;
            Thread.sleep(50);
        }
        Asserts.assertEquals("比对应在超时前完成(DONE)", "DONE", last);
        st.put("__jobId", jobId);
        return st;
    }

    /** 包比对 happy path：统计/树/版本/自动排序。 */
    public void testPackageCompareHappyPath() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-flow");
        try {
            Path oldWar = writeWar(tmp, "bempdemo-1.0.0.war", new LinkedHashMap<String, String>() {{
                put("static/app.js", "console.log(1)\n");
                put("static/util.js", "function shared(){}\n");
                put("WEB-INF/web.xml", "<web-app>v1</web-app>\n");
            }}, "1.0.0");
            Path newWar = writeWar(tmp, "bempdemo-1.0.1.war", new LinkedHashMap<String, String>() {{
                put("static/app.js", "console.log(2)\n");
                put("static/util.js", "function shared(){}\n");
                put("WEB-INF/web.xml", "<web-app>v1</web-app>\n");
                put("static/new.js", "var added=1;\n");
            }}, "1.0.1");

            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));

            String body = ServerTestHarness.compareBody("package", oldWar.toString(), newWar.toString());
            HttpResponse<String> submit = s.post("/api/session/compare", body);
            Asserts.assertEquals("提交应 200", 200, submit.statusCode());
            Map<String, Object> sub = json(submit);
            Asserts.assertEquals("mode 应为 package", "package", sub.get("mode"));
            // T02741：不再按文件名版本号自动重排，autoOrdered 恒为 false；左=老包/右=新包由用户显式放置决定。
            Asserts.assertEquals("不再自动重排（尊重用户左/右放置）", Boolean.FALSE, sub.get("autoOrdered"));
            String jobId = String.valueOf(sub.get("jobId"));

            Map<String, Object> st = pollUntil(s, jobId, "DONE");
            Asserts.assertEquals("版本自动排序 old=1.0.0", "1.0.0", st.get("oldVersion"));
            Asserts.assertEquals("版本自动排序 new=1.0.1", "1.0.1", st.get("newVersion"));

            Map<String, Object> stats = (Map<String, Object>) st.get("stats");
            Asserts.assertNotNull("应含 stats", stats);
            long total = ((Number) stats.get("total")).longValue();
            long added = ((Number) stats.get("added")).longValue();
            long modified = ((Number) stats.get("modified")).longValue();
            Asserts.assertTrue("total 应 >0", total > 0);
            Asserts.assertTrue("应识别至少一个新增(new.js)", added >= 1);
            Asserts.assertTrue("应识别至少一个修改(app.js)", modified >= 1);

            Object tree = st.get("tree");
            Asserts.assertTrue("tree 应为 List", tree instanceof List);
            List<Object> nodes = (List<Object>) tree;
            Asserts.assertTrue("tree 节点数应与 total 一致", nodes.size() == total);
            Asserts.assertTrue("应含 app.js 节点", keyInTree(nodes, "static/app.js"));
            Asserts.assertTrue("应含 new.js 节点", keyInTree(nodes, "static/new.js"));
        } finally {
            deleteTree(tmp);
        }
    }

    /** entry 取单文件内容（文本 diff）→ export 打包(zip) → export/start 小包 → report → job 取消/未知操作。 */
    public void testEntryExportReportFlow() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-flow2");
        try {
            Path oldWar = writeWar(tmp, "demoapp-2.0.0.war", new LinkedHashMap<String, String>() {{
                put("static/app.js", "line-a\nline-b\n");
                put("static/keep.js", "same\n");
            }}, "2.0.0");
            Path newWar = writeWar(tmp, "demoapp-2.0.1.war", new LinkedHashMap<String, String>() {{
                put("static/app.js", "line-a\nline-CHANGED\n");
                put("static/keep.js", "same\n");
                put("static/add.js", "brand-new\n");
            }}, "2.0.1");

            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));
            String jobId = submitAndWaitDone(s,
                    ServerTestHarness.compareBody("package", oldWar.toString(), newWar.toString()))
                    .get("__jobId").toString();

            // 1) entry decompile（文本 diff）
            String key = URLEncoder.encode("static/app.js", "UTF-8");
            HttpResponse<String> entry = s.get("/api/entry/decompile?jobId=" + jobId + "&key=" + key);
            Asserts.assertEquals("entry 应 200", 200, entry.statusCode());
            Map<String, Object> ej = json(entry);
            Asserts.assertEquals("entry key 回显", "static/app.js", ej.get("key"));
            Asserts.assertTrue("entry 应带 engine 字段", ej.containsKey("engine"));
            Asserts.assertTrue("entry 应带 diffText 字段", ej.containsKey("diffText"));
            Asserts.assertEquals("文本差异应 ok=true", Boolean.TRUE, ej.get("ok"));

            // 2) 导出（同步 GET /api/job/{id}/export）应为 zip
            HttpResponse<byte[]> exp = s.getRaw("/api/job/" + jobId + "/export");
            Asserts.assertEquals("export 应 200", 200, exp.statusCode());
            Asserts.assertTrue("导出体应非空", exp.body().length > 22);
            Asserts.assertTrue("应为 zip(PK 魔数)", exp.body()[0] == 'P' && exp.body()[1] == 'K');

            // 3) 小包 export/start（POST）同样应流式返回 zip
            HttpResponse<byte[]> exp2 = s.postRaw("/api/job/" + jobId + "/export/start", "{}");
            Asserts.assertEquals("export/start 应 200", 200, exp2.statusCode());
            Asserts.assertTrue("export/start 应为 zip", exp2.body().length > 22
                    && exp2.body()[0] == 'P' && exp2.body()[1] == 'K');

            // 3.1) P2-4：样本不含 lib/*.jar 差异，JAR 粒度应 409 而不是下发空 zip
            HttpResponse<String> jarEmpty = s.post("/api/job/" + jobId + "/export/start",
                    "{\"granularity\":\"jar\"}");
            Asserts.assertEquals("无变化 jar 时 JAR 粒度应 409", 409, jarEmpty.statusCode());
            Asserts.assertContains("应说明缺少 lib/*.jar 差异",
                    String.valueOf(json(jarEmpty).get("error")), "lib/*.jar");

            // 4) Markdown 报告
            HttpResponse<String> rep = s.get("/api/job/" + jobId + "/report");
            Asserts.assertEquals("report 应 200", 200, rep.statusCode());
            Asserts.assertContains("报告应带 markdown mime",
                    rep.headers().firstValue("Content-Type").orElse(""), "text/markdown");
            Asserts.assertTrue("报告体应非空", rep.body().length() > 20);

            // 5) job 取消（DONE 后仍应受理并回报 cancelled=true）
            HttpResponse<String> cancel = s.post("/api/job/" + jobId + "/cancel", "{}");
            Asserts.assertEquals("cancel 应 200", 200, cancel.statusCode());
            Asserts.assertEquals("cancel 应回报 cancelled=true", Boolean.TRUE, json(cancel).get("cancelled"));

            // 6) 未知 job 操作 → 404
            HttpResponse<String> bogus = s.get("/api/job/" + jobId + "/bogus-action");
            Asserts.assertEquals("未知 job 操作应 404", 404, bogus.statusCode());
            Asserts.assertContains("应含未知 job 操作提示", String.valueOf(json(bogus).get("error")), "未知 job 操作");
        } finally {
            deleteTree(tmp);
        }
    }

    /** folder 模式比对 + /api/file 磁盘操作（info）。 */
    public void testFolderCompareAndFileOp() throws Exception {
        Path tmp = Files.createTempDirectory("bdf-foldercmp");
        try {
            Path dirOld = Files.createDirectories(tmp.resolve("old"));
            Path dirNew = Files.createDirectories(tmp.resolve("new"));
            Files.writeString(dirOld.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
            Files.writeString(dirOld.resolve("b.txt"), "shared\n", StandardCharsets.UTF_8);
            Files.writeString(dirNew.resolve("a.txt"), "world\n", StandardCharsets.UTF_8); // modified
            Files.writeString(dirNew.resolve("b.txt"), "shared\n", StandardCharsets.UTF_8); // unchanged
            Files.writeString(dirNew.resolve("c.txt"), "added\n", StandardCharsets.UTF_8);  // added

            ServerConfig config = newConfig(tmp);
            ServerTestHarness.Server s = launch(config, Files.createDirectories(tmp.resolve("webroot")));
            Map<String, Object> st = submitAndWaitDone(s,
                    ServerTestHarness.compareBody("folder", dirOld.toString(), dirNew.toString()));
            String jobId = st.get("__jobId").toString();
            Asserts.assertEquals("folder 模式应回带", "folder", st.get("mode"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", jobId);
            body.put("key", "a.txt");
            body.put("op", "info");
            HttpResponse<String> r = s.post("/api/file", com.bempdiff.server.Json.write(body));
            Asserts.assertEquals("file info 应 200", 200, r.statusCode());
            Map<String, Object> j = json(r);
            Asserts.assertEquals("info 应 ok=true", Boolean.TRUE, j.get("ok"));
            Asserts.assertEquals("key 回显", "a.txt", j.get("key"));
            Asserts.assertTrue("应带 side 字段", j.containsKey("side"));
            Asserts.assertTrue("a.txt 应判为 MODIFIED", "MODIFIED".equals(j.get("status")));

            // 未知文件操作 → 400
            Map<String, Object> badOp = new LinkedHashMap<>();
            badOp.put("jobId", jobId);
            badOp.put("key", "a.txt");
            badOp.put("op", "frobnicate");
            HttpResponse<String> r2 = s.post("/api/file", com.bempdiff.server.Json.write(badOp));
            Asserts.assertEquals("未知文件操作应 400", 400, r2.statusCode());
        } finally {
            deleteTree(tmp);
        }
    }

    // ---------------- 夹具/工具 ----------------

    private static Path writeWar(Path dir, String fileName, Map<String, String> textEntries, String version) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : textEntries.entrySet()) {
            entries.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        byte[] war = TestFixtures.makeWar(entries, version);
        Path p = dir.resolve(fileName);
        Files.write(p, war);
        return p;
    }

    private static Map<String, Object> pollUntil(ServerTestHarness.Server s, String jobId, String expect) throws Exception {
        Map<String, Object> st = null;
        String last = "";
        for (int i = 0; i < 400; i++) {
            HttpResponse<String> rs = s.get("/api/job/" + jobId + "/status");
            st = json(rs);
            last = String.valueOf(st.get("status"));
            if (expect.equals(last) || "ERROR".equals(last) || "CANCELLED".equals(last)) break;
            Thread.sleep(50);
        }
        Asserts.assertEquals("job 应到达 " + expect, expect, last);
        return st;
    }

    @SuppressWarnings("unchecked")
    private static boolean keyInTree(List<Object> nodes, String key) {
        for (Object o : nodes) {
            if (o instanceof Map && key.equals(((Map<String, Object>) o).get("key"))) return true;
        }
        return false;
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

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全端点覆盖冒烟（真实产品 API 18090 + 性能 Harness 18080）。

目的：在性能压测前，把两个 SUT 的每一条路由各打一次，产出可审计的覆盖矩阵
（路由 → 状态码 / 耗时 / 备注），确保「测试用例覆盖所有 API 端点，不可遗漏」。

设计要点：
  - 每个检查行都绑定一个 catalog 路由键；结束时逐条核对 catalog，缺一即失败。
  - 真实服务是有状态的（compare → job → entry/export/AI），本脚本用轮询串起完整生命周期；
    大包异步导出用「单条目 257MB」夹具触发（bytes > 256MB），覆盖 202 与 /api/export 管理链路。
  - 所有写操作只落在隔离目录（.workbuddy/tmp/perf/env）与隔离 user.home；
    生产端口 18765 与真实 ~/.bempdiff 完全不参与。
  - 只按 pid 文件定向关停由 perf_env.py 负责，本脚本不做任何进程操作。

用法：
  python tooling/scripts/perf/smoke_api.py            # 全量
  python tooling/scripts/perf/smoke_api.py --no-heavy # 跳过 200MB 取消用例（快速回归）
"""
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from perf_env import load_cfg, p  # noqa: E402  (复用同一套配置解析/路径规则)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

CFG = load_cfg()
SRV = "http://127.0.0.1:%d" % CFG["server"]["port"]
HAR = "http://127.0.0.1:%d" % CFG["harness"]["port"]
ENV_ROOT = p(CFG["env_root"])
OUT_DIR = os.path.join(ROOT, "tooling", "jmeter", "results", "perf-2026-11-01", "smoke")
STAMP = datetime.now().strftime("%Y%m%d-%H%M%S")
HEAVY = "--no-heavy" not in sys.argv

SERVER_CATALOG = [
    ("POST /api/session/compare", "提交比对任务（package / folder）"),
    ("GET /api/tasks/running", "查询进行中任务（桌面壳防误关）"),
    ("GET /api/job/{jobId}/status", "任务状态 / 差异树"),
    ("POST /api/job/{jobId}/cancel", "请求取消任务"),
    ("GET|POST /api/job/{jobId}/report", "生成 Markdown 报告（含 AI 分支）"),
    ("GET /api/job/{jobId}/unpack-report", "嵌套解包状态报告"),
    ("GET|POST /api/job/{jobId}/export", "同步导出差异资产 zip（旧调用）"),
    ("POST /api/job/{jobId}/export/start", "导出分流：小包同步 / 大包异步"),
    ("GET|POST /api/job/{jobId}/ai-analyze", "AI 流式分析（SSE）"),
    ("POST /api/job/{jobId}/ai-classify", "AI 智能分类（stageB）"),
    ("POST /api/job/{jobId}/ai-estimate", "AI token 成本预估"),
    ("GET /api/export/list", "导出记录列表"),
    ("GET /api/export/{id}", "导出记录详情"),
    ("GET /api/export/{id}/download", "下载异步导出 zip"),
    ("POST|DELETE /api/export/{id}/delete", "删除导出记录"),
    ("GET /api/export", "导出记录列表（空子路径别名）"),
    ("GET /api/entry/decompile", "单文件反编译"),
    ("GET /api/entry/children", "归档子项展开"),
    ("GET /api/entry/recursive", "归档递归解包"),
    ("POST /api/file", "文件夹模式文件操作（info/copy/rename/delete）"),
    ("GET|PUT /api/config", "配置读取 / 更新"),
    ("POST /api/ai/test", "AI 连接测试"),
    ("POST /api/ai/models", "拉取可用模型列表"),
    ("GET|POST /api/ai/context", "项目上下文索引（读取 / 刷新）"),
    ("POST /api/admin/cleanup", "手动清理临时文件"),
    ("GET /api/admin/diag-bundle", "导出脱敏诊断包"),
    ("POST /api/update/check", "版本更新检查（外部依赖）"),
    ("GET /", "静态资源 / 占位首页"),
]

HARNESS_CATALOG = [
    ("GET /health", "Harness 存活探针"),
    ("GET /api/parse", "双包解析"),
    ("GET /api/diff", "差异计算"),
    ("GET /api/decompile/batch", "并发反编译候选"),
    ("GET /api/ai/stageA", "阶段 A（总体摘要）"),
    ("GET /api/ai/stageB", "阶段 B（逐文件深读）"),
    ("GET /api/report", "Markdown 报告"),
    ("GET /api/export/classes", "导出差异 class"),
    ("GET /api/export/jars", "导出差异 jar"),
    ("GET /api/export/sources", "导出反编译源码"),
    ("GET /api/audit", "写审计日志"),
    ("GET /api/audit/query", "查询审计日志"),
]

ROWS = []


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def req(self, method, path, body=None, timeout=600, stream_to=None):
        url = self.base + path
        data = None
        headers = {"Accept": "*/*"}
        if body is not None:
            if isinstance(body, (dict, list)):
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")
                headers["Content-Type"] = "application/json"
            else:
                data = str(body).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        t0 = time.perf_counter()
        status, text, size, err = 0, "", 0, None
        try:
            resp = self.opener.open(request, timeout=timeout)
            status = resp.status
            if stream_to:
                size = 0
                with open(stream_to, "wb") as f:
                    while True:
                        chunk = resp.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                        size += len(chunk)
                text = ""
            else:
                raw = resp.read()
                size = len(raw)
                text = raw.decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            status = e.code
            raw = e.read()
            size = len(raw)
            text = raw.decode("utf-8", "replace")
        except Exception as e:  # 连接失败 / 超时
            err = repr(e)
        return {"status": status, "text": text, "size": size, "error": err,
                "ms": round((time.perf_counter() - t0) * 1000, 1), "url": url}


def jbody(resp):
    try:
        return json.loads(resp["text"]) if resp["text"] else None
    except Exception:
        return None


def ok_json(resp):
    j = jbody(resp)
    return bool(j) and j.get("ok") is not False


def rec(cid, group, title, method, path, resp, route=None, expect=(200,),
        extra_ok=True, note=""):
    ok = resp["status"] in expect and resp["error"] is None and bool(extra_ok)
    row = {
        "id": cid, "group": group, "title": title, "method": method, "path": path,
        "status": resp["status"], "ms": resp["ms"], "ok": ok, "route": route,
        "note": note, "error": resp["error"],
        "snippet": (resp["text"] or "")[:400].replace("\n", " "),
    }
    ROWS.append(row)
    print("[%s] %-7s %-44s %3s %8.1fms %s" %
          ("OK  " if ok else "FAIL", cid, title, resp["status"], resp["ms"], note), flush=True)
    return row


def start_compare(client, left, right, left_type="package", options=None):
    body = {"leftType": left_type, "leftPath": left, "rightPath": right}
    if options is not None:
        body["options"] = options
    return client.req("POST", "/api/session/compare", body=body, timeout=120)


def wait_job(client, job_id, timeout=600, interval=0.4):
    t0 = time.time()
    last = None
    polls = 0
    while time.time() - t0 < timeout:
        r = client.req("GET", "/api/job/%s/status" % job_id, timeout=60)
        polls += 1
        j = jbody(r)
        if j:
            last = j
        if (j or {}).get("status") in ("DONE", "FAILED", "CANCELLED"):
            return j, r, polls, time.time() - t0
        time.sleep(interval)
    return last, r, polls, time.time() - t0


def first_node(tree, pred):
    for n in tree or []:
        try:
            if pred(n):
                return n
        except Exception:
            pass
    return None


def qkey(key):
    return urllib.parse.quote(key, safe="")


def zip_note(path):
    try:
        if not zipfile.is_zipfile(path):
            return "非 zip"
        with zipfile.ZipFile(path) as z:
            return "zip entries=%d bytes=%d" % (len(z.namelist()), os.path.getsize(path))
    except Exception as e:
        return "zip 校验失败: %s" % e


def fixtures(name):
    left, right = CFG["fixtures"][name].split(",")
    return p(left.strip()), p(right.strip())


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    work = os.path.join(ENV_ROOT, "smoke")
    os.makedirs(work, exist_ok=True)

    srv = Client(SRV)
    har = Client(HAR)
    small_l, small_r = fixtures("small")
    nested_l, nested_r = fixtures("nested_zip")
    folder_l, folder_r = fixtures("folder")
    many_l, many_r = fixtures("largebytes")
    big_l, big_r = fixtures("big200")

    # ================= 0. 环境 =================
    r = har.req("GET", "/health", timeout=10)
    rec("ENV-01", "环境", "Harness /health", "GET", "/health", r,
        route="GET /health", extra_ok=ok_json(r))

    # ================= 1. Harness 全部端点 =================
    hq = "?old=%s&new=%s" % (urllib.parse.quote(small_l), urllib.parse.quote(small_r))
    hq_expand = hq + "&expandAll=true"
    for cid, route, title, path, expect_ok in [
        ("H-01", "GET /api/parse", "双包解析", "/api/parse" + hq_expand, True),
        ("H-02", "GET /api/diff", "差异计算", "/api/diff" + hq_expand, True),
        ("H-03", "GET /api/decompile/batch", "并发反编译", "/api/decompile/batch" + hq_expand + "&threads=8&topK=12", True),
        ("H-04", "GET /api/ai/stageA", "AI 阶段 A", "/api/ai/stageA" + hq_expand + "&topK=12", True),
        ("H-05", "GET /api/ai/stageB", "AI 阶段 B", "/api/ai/stageB" + hq_expand + "&topK=8", True),
        ("H-06", "GET /api/report", "Markdown 报告", "/api/report" + hq_expand + "&topK=12", True),
        ("H-07", "GET /api/export/classes", "导出差异 class", "/api/export/classes" + hq_expand + "&topK=12", True),
        ("H-08", "GET /api/export/jars", "导出差异 jar", "/api/export/jars" + hq_expand + "&topK=12", True),
        ("H-09", "GET /api/export/sources", "导出反编译源码", "/api/export/sources" + hq_expand + "&topK=12", True),
        ("H-10", "GET /api/audit", "写审计日志", "/api/audit?old=%s&new=%s" % (urllib.parse.quote(small_l), urllib.parse.quote(small_r)), True),
        ("H-11", "GET /api/audit/query", "查询审计日志", "/api/audit/query?from=2020-01-01&to=2030-01-01", True),
    ]:
        r = har.req("GET", path, timeout=600)
        note = ""
        j = jbody(r)
        if j and j.get("elapsedMs") is not None:
            note = "elapsedMs=%s" % j.get("elapsedMs")
        rec(cid, "Harness", title, "GET", path.split("?")[0], r, route=route,
            extra_ok=(ok_json(r) if expect_ok else True), note=note)

    # ================= 2. 真实服务：小包完整生命周期 =================
    r = start_compare(srv, small_l, small_r, options={"expandAll": True, "topK": 12})
    j = jbody(r) or {}
    job_small = j.get("jobId")
    rec("S-01", "比对", "提交小包比对", "POST", "/api/session/compare", r,
        route="POST /api/session/compare", extra_ok=bool(job_small), note="jobId=%s" % job_small)

    if job_small:
        job, r, polls, secs = wait_job(srv, job_small)
        rec("S-02", "任务", "轮询至完成", "GET", "/api/job/{id}/status", r,
            route="GET /api/job/{jobId}/status", extra_ok=(job or {}).get("status") == "DONE",
            note="status=%s polls=%d %0.1fs" % ((job or {}).get("status"), polls, secs))
        tree = (job or {}).get("tree") or []

        class_node = first_node(tree, lambda n: n.get("fileClass") == "CLASS"
                                and n.get("status") in ("MODIFIED", "ADDED", "DELETED"))
        if not class_node:
            class_node = first_node(tree, lambda n: str(n.get("key", "")).endswith(".class"))
        key = (class_node or {}).get("key", "")
        if key:
            path = "/api/entry/decompile?jobId=%s&key=%s" % (job_small, qkey(key))
            r = srv.req("GET", path, timeout=300)
            jj = jbody(r) or {}
            rec("S-03", "条目", "单文件反编译", "GET", "/api/entry/decompile", r,
                route="GET /api/entry/decompile", extra_ok=bool(jj.get("key")),
                note="key=%s ok=%s" % (key[:50], jj.get("ok")))

        r = srv.req("GET", "/api/job/%s/report" % job_small, timeout=300)
        rec("S-04", "报告", "GET 生成报告", "GET", "/api/job/{id}/report", r,
            route="GET|POST /api/job/{jobId}/report", note="%d bytes" % r["size"])

        r = srv.req("POST", "/api/job/%s/report" % job_small, body={"ai": False}, timeout=300)
        rec("S-05", "报告", "POST 生成报告（非 AI）", "POST", "/api/job/{id}/report", r,
            route="GET|POST /api/job/{jobId}/report", note="%d bytes" % r["size"])

        r = srv.req("POST", "/api/job/%s/report" % job_small, body={"ai": True, "category": "risk"}, timeout=600)
        rec("S-06", "报告", "POST 生成报告（AI）", "POST", "/api/job/{id}/report", r,
            route="GET|POST /api/job/{jobId}/report", note="%d bytes" % r["size"])

        r = srv.req("GET", "/api/job/%s/unpack-report" % job_small, timeout=60)
        jj = jbody(r) or {}
        rec("S-07", "报告", "解包状态报告", "GET", "/api/job/{id}/unpack-report", r,
            route="GET /api/job/{jobId}/unpack-report",
            extra_ok=("enabled" in jj), note="enabled=%s" % jj.get("enabled"))

        # 同步导出（旧调用 + 分粒度）
        for cid, granularity in [("S-08", "all"), ("S-09", "jar"), ("S-10", "class")]:
            out = os.path.join(work, "sync-%s-%s.zip" % (job_small, granularity))
            r = srv.req("POST", "/api/job/%s/export/start" % job_small,
                        body={"granularity": granularity}, timeout=300, stream_to=out)
            note = zip_note(out) if r["status"] == 200 else ""
            rec(cid, "导出", "同步导出 %s" % granularity, "POST", "/api/job/{id}/export/start", r,
                route="POST /api/job/{jobId}/export/start", extra_ok=(r["status"] == 200 and r["size"] > 0),
                note=note)
            if os.path.isfile(out):
                os.remove(out)

        out = os.path.join(work, "legacy-%s.zip" % job_small)
        r = srv.req("GET", "/api/job/%s/export" % job_small, timeout=300, stream_to=out)
        note = zip_note(out) if r["status"] == 200 else ""
        rec("S-11", "导出", "旧版同步导出", "GET", "/api/job/{id}/export", r,
            route="GET|POST /api/job/{jobId}/export", extra_ok=(r["status"] == 200 and r["size"] > 0), note=note)
        if os.path.isfile(out):
            os.remove(out)

        # AI 分析（SSE，全量 + 单文件）
        r = srv.req("POST", "/api/job/%s/ai-analyze" % job_small, body={"category": "risk"}, timeout=900)
        events = r["text"].count("event: ")
        rec("S-12", "AI", "AI 流式分析（全量）", "POST", "/api/job/{id}/ai-analyze", r,
            route="GET|POST /api/job/{jobId}/ai-analyze",
            extra_ok=("event: done" in r["text"] and "event: error" not in r["text"]),
            note="sse_events=%d bytes=%d" % (events, r["size"]))

        if key:
            r = srv.req("POST", "/api/job/%s/ai-analyze" % job_small,
                        body={"category": "risk", "fileKey": key,
                              "fileStatus": (class_node or {}).get("status")}, timeout=900)
            events = r["text"].count("event: ")
            rec("S-13", "AI", "AI 流式分析（单文件）", "POST", "/api/job/{id}/ai-analyze", r,
                route="GET|POST /api/job/{jobId}/ai-analyze",
                extra_ok=("event: done" in r["text"] and "event: error" not in r["text"]),
                note="key=%s events=%d" % (key[:40], events))

        r = srv.req("POST", "/api/job/%s/ai-classify" % job_small, body={}, timeout=900)
        jj = jbody(r) or {}
        rec("S-14", "AI", "AI 智能分类", "POST", "/api/job/{id}/ai-classify", r,
            route="POST /api/job/{jobId}/ai-classify",
            extra_ok=isinstance(jj.get("items"), list), note="items=%s" % len(jj.get("items") or []))

        r = srv.req("POST", "/api/job/%s/ai-estimate" % job_small,
                    body={"category": "risk"}, timeout=600)
        jj = jbody(r) or {}
        rec("S-15", "AI", "AI token 成本预估", "POST", "/api/job/{id}/ai-estimate", r,
            route="POST /api/job/{jobId}/ai-estimate",
            extra_ok=("analyze" in jj or "threshold" in jj), note="analyze=%s" % jj.get("analyze"))

        # 包模式文件操作应被明确拒绝（契约）
        r = srv.req("POST", "/api/file", body={"jobId": job_small, "key": key or "x", "op": "info"}, timeout=60)
        rec("S-C1", "契约", "包模式文件操作 → 400", "POST", "/api/file", r,
            expect=(400,), note="上一轮：%s" % (jbody(r) or {}).get("error", ""))

    # ================= 3. 嵌套归档：children / recursive / unpack-report =================
    r = start_compare(srv, nested_l, nested_r, options={"expandAll": True, "topK": 12})
    job_nested = (jbody(r) or {}).get("jobId")
    rec("S-16", "比对", "提交嵌套 zip 比对", "POST", "/api/session/compare", r,
        route="POST /api/session/compare", extra_ok=bool(job_nested), note="jobId=%s" % job_nested)
    if job_nested:
        job, r, polls, secs = wait_job(srv, job_nested)
        rec("S-17", "任务", "嵌套任务完成", "GET", "/api/job/{id}/status", r,
            route="GET /api/job/{jobId}/status", extra_ok=(job or {}).get("status") == "DONE",
            note="status=%s %0.1fs" % ((job or {}).get("status"), secs))
        tree = (job or {}).get("tree") or []
        jar_node = first_node(tree, lambda n: n.get("fileClass") in ("JAR", "ARCHIVE")
                              and not str(n.get("key", "")).endswith("/"))
        jar_key = (jar_node or {}).get("key")
        if jar_key:
            r = srv.req("GET", "/api/entry/children?jobId=%s&key=%s" % (job_nested, qkey(jar_key)), timeout=300)
            jj = jbody(r)
            rec("S-18", "条目", "归档子项展开", "GET", "/api/entry/children", r,
                route="GET /api/entry/children", extra_ok=isinstance(jj, list),
                note="key=%s children=%s" % (jar_key, len(jj or [])))
            r = srv.req("GET", "/api/entry/recursive?jobId=%s&key=%s" % (job_nested, qkey(jar_key)), timeout=300)
            jj = jbody(r)
            shape = ("list:%d" % len(jj)) if isinstance(jj, list) else "dict:%d" % len(jj or {})
            rec("S-19", "条目", "归档递归解包", "GET", "/api/entry/recursive", r,
                route="GET /api/entry/recursive", extra_ok=isinstance(jj, (list, dict)),
                note="key=%s %s" % (jar_key, shape))
        else:
            rec("S-18", "条目", "归档子项展开", "GET", "/api/entry/children",
                {"status": 0, "text": "", "size": 0, "error": "tree 中未找到归档节点", "ms": 0},
                route="GET /api/entry/children", extra_ok=False)
            rec("S-19", "条目", "归档递归解包", "GET", "/api/entry/recursive",
                {"status": 0, "text": "", "size": 0, "error": "tree 中未找到归档节点", "ms": 0},
                route="GET /api/entry/recursive", extra_ok=False)

    r = start_compare(srv, nested_l, nested_r,
                      options={"expandAll": True, "unpackNested": True, "unpackThreads": 4})
    job_unpack = (jbody(r) or {}).get("jobId")
    rec("S-20", "比对", "提交物理解包比对", "POST", "/api/session/compare", r,
        route="POST /api/session/compare", extra_ok=bool(job_unpack), note="jobId=%s" % job_unpack)
    if job_unpack:
        job, r, polls, secs = wait_job(srv, job_unpack, timeout=900)
        rec("S-21", "任务", "物理解包任务完成", "GET", "/api/job/{id}/status", r,
            route="GET /api/job/{jobId}/status", extra_ok=(job or {}).get("status") == "DONE",
            note="status=%s %0.1fs" % ((job or {}).get("status"), secs))
        r = srv.req("GET", "/api/job/%s/unpack-report" % job_unpack, timeout=60)
        jj = jbody(r) or {}
        rec("S-22", "报告", "解包报告 enabled=true", "GET", "/api/job/{id}/unpack-report", r,
            route="GET /api/job/{jobId}/unpack-report", extra_ok=jj.get("enabled") is True,
            note="enabled=%s reports=%d" % (jj.get("enabled"), len(jj.get("reports") or [])))

    # ================= 4. 文件夹模式 + 文件操作 =================
    ops_root = os.path.join(ENV_ROOT, "folders_ops")
    shutil.rmtree(ops_root, ignore_errors=True)
    shutil.copytree(folder_l, os.path.join(ops_root, "v1"))
    shutil.copytree(folder_r, os.path.join(ops_root, "v2"))
    ops_l = os.path.join(ops_root, "v1")
    ops_r = os.path.join(ops_root, "v2")

    r = start_compare(srv, ops_l, ops_r, left_type="folder",
                      options={"expandAll": True, "topK": 12})
    job_folder = (jbody(r) or {}).get("jobId")
    rec("S-23", "比对", "提交文件夹比对", "POST", "/api/session/compare", r,
        route="POST /api/session/compare", extra_ok=bool(job_folder), note="jobId=%s" % job_folder)
    if job_folder:
        job, r, polls, secs = wait_job(srv, job_folder)
        rec("S-24", "任务", "文件夹任务完成", "GET", "/api/job/{id}/status", r,
            route="GET /api/job/{jobId}/status", extra_ok=(job or {}).get("status") == "DONE",
            note="status=%s %0.1fs" % ((job or {}).get("status"), secs))
        tree = (job or {}).get("tree") or []
        mod_node = first_node(tree, lambda n: n.get("status") == "MODIFIED")
        del_node = first_node(tree, lambda n: n.get("status") == "DELETED")
        add_node = first_node(tree, lambda n: n.get("status") == "ADDED")

        def file_op(cid, title, payload, expect=(200,), note=""):
            r = srv.req("POST", "/api/file", body=payload, timeout=120)
            jj = jbody(r) or {}
            return rec(cid, "文件操作", title, "POST", "/api/file", r,
                       route="POST /api/file", expect=expect,
                       extra_ok=(expect != (200,) or jj.get("ok") is not False),
                       note=note or "code=%s msg=%s" % (jj.get("code"), (jj.get("message") or "")[:40]))

        mod_key = (mod_node or {}).get("key")
        new_name = "renamed_" + os.path.basename(mod_key) if mod_key else None
        renamed_key = (os.path.dirname(mod_key).replace("\\", "/") + "/" + new_name) if mod_key else None
        if mod_key:
            file_op("S-25", "info", {"jobId": job_folder, "key": mod_key, "op": "info"})
        if del_node:
            file_op("S-26", "copy 左→右（DELETED）", {"jobId": job_folder, "key": del_node["key"], "op": "copy"})
        if add_node:
            file_op("S-27", "copy 右→左（ADDED）", {"jobId": job_folder, "key": add_node["key"], "op": "copy"})
        if mod_key and new_name:
            file_op("S-28", "rename", {"jobId": job_folder, "key": mod_key, "op": "rename", "newName": new_name})
            file_op("S-29", "delete（重命名后）", {"jobId": job_folder, "key": renamed_key, "op": "delete"})
        file_op("S-C2", "未知 op → 400", {"jobId": job_folder, "key": mod_key or "x", "op": "nope"}, expect=(400,))

    # ================= 5. 大包异步导出（单条目 257MB）+ 导出管理 =================
    r = start_compare(srv, many_l, many_r, options={"expandAll": False, "topK": 12})
    j = jbody(r) or {}
    job_many = j.get("jobId")
    rec("S-30", "比对", "提交 257MB 单文件比对（触发异步导出）", "POST", "/api/session/compare", r,
        route="POST /api/session/compare", extra_ok=bool(job_many), note="jobId=%s" % job_many)
    if job_many:
        job, r, polls, secs = wait_job(srv, job_many, timeout=900)
        stats = (job or {}).get("stats") or {}
        rec("S-31", "任务", "多文件任务完成", "GET", "/api/job/{id}/status", r,
            route="GET /api/job/{jobId}/status", extra_ok=(job or {}).get("status") == "DONE",
            note="status=%s %0.1fs stats=%s" % ((job or {}).get("status"), secs,
                                               {k: stats.get(k) for k in ("modified", "added", "deleted")}))

        r = srv.req("GET", "/api/export/list", timeout=60)
        rec("S-32", "导出", "导出记录列表", "GET", "/api/export/list", r,
            route="GET /api/export/list", extra_ok=isinstance(jbody(r), list),
            note="records=%d" % len(jbody(r) or []))

        r = srv.req("GET", "/api/export/", timeout=60)
        rec("S-33", "导出", "列表别名（空子路径）", "GET", "/api/export", r,
            route="GET /api/export", extra_ok=isinstance(jbody(r), list))

        exp_path = os.path.join(work, "export-start-%s.zip" % job_many)
        r = srv.req("POST", "/api/job/%s/export/start" % job_many,
                    body={"granularity": "all"}, timeout=300, stream_to=exp_path)
        if r["status"] == 202 and os.path.isfile(exp_path):
            # 202 是 JSON 记录；stream_to 路径同时兼容「阈值判定失败退回 200 zip」的情况，避免整包读入内存
            try:
                with open(exp_path, encoding="utf-8", errors="replace") as f:
                    r["text"] = f.read()
                r["size"] = len(r["text"])
            except Exception:
                pass
        if os.path.isfile(exp_path):
            os.remove(exp_path)
        jj = jbody(r) or {}
        export_id = jj.get("id")
        rec("S-34", "导出", "异步导出 → 202", "POST", "/api/job/{id}/export/start", r,
            expect=(202,), extra_ok=bool(export_id),
            note="id=%s files=%s bytes=%s" % (export_id, jj.get("incrementCount"), jj.get("estimatedBytes")))

        if export_id:
            final = None
            t0 = time.time()
            while time.time() - t0 < 600:
                rr = srv.req("GET", "/api/export/list", timeout=60)
                for item in jbody(rr) or []:
                    if item.get("id") == export_id:
                        final = item
                        break
                if final and final.get("status") in ("done", "error"):
                    break
                time.sleep(0.3)
            rec("S-35", "导出", "异步导出完成", "GET", "/api/export/list",
                rr, route="GET /api/export/list",
                extra_ok=(final or {}).get("status") == "done",
                note="status=%s size=%s %0.1fs" % ((final or {}).get("status"),
                                                  (final or {}).get("size"), time.time() - t0))

            r = srv.req("GET", "/api/export/%s" % export_id, timeout=60)
            jj = jbody(r) or {}
            rec("S-36", "导出", "导出记录详情", "GET", "/api/export/{id}", r,
                route="GET /api/export/{id}", extra_ok=jj.get("id") == export_id,
                note="status=%s" % jj.get("status"))

            out = os.path.join(work, "async-%s.zip" % export_id)
            r = srv.req("GET", "/api/export/%s/download" % export_id, timeout=600, stream_to=out)
            note = zip_note(out) if r["status"] == 200 else ""
            rec("S-37", "导出", "下载异步导出 zip", "GET", "/api/export/{id}/download", r,
                route="GET /api/export/{id}/download",
                extra_ok=(r["status"] == 200 and r["size"] > 0), note=note)
            if os.path.isfile(out):
                os.remove(out)

            r = srv.req("POST", "/api/export/%s/delete" % export_id, timeout=60)
            rec("S-38", "导出", "删除导出记录", "POST", "/api/export/{id}/delete", r,
                route="POST|DELETE /api/export/{id}/delete", extra_ok=ok_json(r))

            r = srv.req("GET", "/api/export/%s" % export_id, timeout=60)
            rec("S-C3", "契约", "删除后详情 → 404", "GET", "/api/export/{id}", r, expect=(404,))

    # ================= 6. 取消 + 进行中任务（200MB 包） =================
    if HEAVY:
        r = start_compare(srv, big_l, big_r, options={"expandAll": False, "topK": 12})
        j = jbody(r) or {}
        job_big = j.get("jobId")
        rec("S-39", "比对", "提交 200MB 比对", "POST", "/api/session/compare", r,
            route="POST /api/session/compare", extra_ok=bool(job_big), note="jobId=%s" % job_big)
        if job_big:
            r = srv.req("POST", "/api/job/%s/cancel" % job_big, timeout=60)
            jj = jbody(r) or {}
            rec("S-40", "任务", "请求取消", "POST", "/api/job/{id}/cancel", r,
                route="POST /api/job/{jobId}/cancel", extra_ok=jj.get("cancelled") is True,
                note="status=%s cancelled=%s" % (jj.get("status"), jj.get("cancelled")))
            r = srv.req("GET", "/api/tasks/running", timeout=60)
            jj = jbody(r)
            rec("S-41", "任务", "进行中任务查询", "GET", "/api/tasks/running", r,
                route="GET /api/tasks/running", note="running=%s" % (len(jj) if isinstance(jj, list) else "?"))
            job, r, polls, secs = wait_job(srv, job_big, timeout=600)
            rec("S-42", "任务", "取消生效（CANCELLED）", "GET", "/api/job/{id}/status", r,
                route="GET /api/job/{jobId}/status",
                extra_ok=(job or {}).get("status") == "CANCELLED",
                note="status=%s polls=%d %0.1fs" % ((job or {}).get("status"), polls, secs))

    # ================= 7. 配置 / AI 辅助 / 管理 / 静态 =================
    r = srv.req("GET", "/api/config", timeout=60)
    rec("S-43", "配置", "读取配置", "GET", "/api/config", r,
        route="GET|PUT /api/config", extra_ok=isinstance(jbody(r), dict))

    r = srv.req("PUT", "/api/config", body={"topK": 12, "stageBTopK": 15}, timeout=60)
    rec("S-44", "配置", "更新配置", "PUT", "/api/config", r,
        route="GET|PUT /api/config", extra_ok=isinstance(jbody(r), dict))

    r = srv.req("DELETE", "/api/config", timeout=60)
    rec("S-C4", "契约", "配置 DELETE → 405", "DELETE", "/api/config", r, expect=(405,))

    r = srv.req("POST", "/api/ai/test", timeout=120)
    jj = jbody(r) or {}
    rec("S-45", "AI", "AI 连接测试（mock 上游）", "POST", "/api/ai/test", r,
        route="POST /api/ai/test", extra_ok=jj.get("ok") is True, note="msg=%s" % jj.get("message"))

    r = srv.req("POST", "/api/ai/models", timeout=120)
    jj = jbody(r) or {}
    rec("S-46", "AI", "获取模型列表", "POST", "/api/ai/models", r,
        route="POST /api/ai/models", extra_ok=bool(jj.get("models")),
        note="models=%s" % jj.get("models"))

    ctx_dir = os.path.join(ENV_ROOT, "folders", "v1")
    r = srv.req("GET", "/api/ai/context?dir=%s" % urllib.parse.quote(ctx_dir), timeout=300)
    jj = jbody(r) or {}
    rec("S-47", "AI", "项目上下文读取", "GET", "/api/ai/context", r,
        route="GET|POST /api/ai/context", extra_ok=jj.get("ok") is True,
        note="projects=%s" % len(jj.get("projects") or []))

    r = srv.req("POST", "/api/ai/context", body={"dir": ctx_dir, "refresh": True}, timeout=300)
    jj = jbody(r) or {}
    rec("S-48", "AI", "项目上下文刷新", "POST", "/api/ai/context", r,
        route="GET|POST /api/ai/context", extra_ok=jj.get("ok") is True,
        note="msg=%s" % (jj.get("message") or "")[:50])

    r = srv.req("GET", "/", timeout=60)
    rec("S-49", "静态", "首页", "GET", "/", r, route="GET /",
        extra_ok=("<html" in (r["text"] or "").lower()))
    r = srv.req("GET", "/index.html", timeout=60)
    rec("S-50", "静态", "index.html", "GET", "/index.html", r, route="GET /",
        extra_ok=(r["status"] == 200))
    r = srv.req("GET", "/assets/__smoke_missing__.js", timeout=60)
    rec("S-51", "静态", "缺失资源回退占位页", "GET", "/assets/__smoke_missing__.js", r,
        route="GET /", extra_ok=(r["status"] == 200))

    r = srv.req("POST", "/api/update/check", body={"current": "0.0.0"}, timeout=60)
    jj = jbody(r) or {}
    rec("S-52", "管理", "版本更新检查（外部网络）", "POST", "/api/update/check", r,
        route="POST /api/update/check", note="ok=%s msg=%s" % (jj.get("ok"), (jj.get("message") or "")[:40]))

    out = os.path.join(work, "diag-bundle.zip")
    r = srv.req("GET", "/api/admin/diag-bundle", timeout=300, stream_to=out)
    note = zip_note(out) if r["status"] == 200 else ""
    rec("S-53", "管理", "导出诊断包", "GET", "/api/admin/diag-bundle", r,
        route="GET /api/admin/diag-bundle", extra_ok=(r["status"] == 200 and r["size"] > 0), note=note)
    if os.path.isfile(out):
        os.remove(out)

    r = srv.req("POST", "/api/admin/cleanup", timeout=300)
    jj = jbody(r) or {}
    rec("S-54", "管理", "清理临时文件", "POST", "/api/admin/cleanup", r,
        route="POST /api/admin/cleanup", extra_ok=jj.get("ok") is True,
        note="freedBytes=%s files=%s" % (jj.get("freedBytes"), jj.get("files")))

    # ================= 8. 契约：错误路径 =================
    r = start_compare(srv, os.path.join(work, "nope-left.jar"), small_r)
    rec("S-C5", "契约", "不存在输入 → 400", "POST", "/api/session/compare", r, expect=(400,))
    r = srv.req("GET", "/api/job/job-does-not-exist/status", timeout=30)
    rec("S-C6", "契约", "未知任务 → 404", "GET", "/api/job/{id}/status", r, expect=(404,))
    r = srv.req("POST", "/api/export", timeout=30)
    rec("S-C7", "契约", "导出列表 POST → 405", "POST", "/api/export", r, expect=(405,))
    r = srv.req("GET", "/api/entry/decompile?jobId=%s" % (job_small or "x"), timeout=30)
    rec("S-C8", "契约", "缺 key → 400", "GET", "/api/entry/decompile", r, expect=(400,))
    r = srv.req("GET", "/api/job/%s/unknown-action" % (job_small or "x"), timeout=30)
    rec("S-C9", "契约", "未知 job 动作 → 404", "GET", "/api/job/{id}/unknown", r, expect=(404,))

    # ================= 9. 覆盖核对与落盘 =================
    covered = {}
    for row in ROWS:
        if row["route"] and row["ok"]:
            covered[row["route"]] = covered.get(row["route"], 0) + 1
    catalog = [("Harness", r, d) for r, d in HARNESS_CATALOG] + [("Server", r, d) for r, d in SERVER_CATALOG]
    missing = [(kind, route, desc) for kind, route, desc in catalog if covered.get(route, 0) == 0]
    failed = [row for row in ROWS if not row["ok"]]

    result = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "server": SRV, "harness": HAR,
        "checks": len(ROWS), "failed": len(failed),
        "catalogRoutes": len(catalog), "coveredRoutes": len(catalog) - len(missing),
        "missing": missing, "rows": ROWS,
    }
    json_path = os.path.join(OUT_DIR, "smoke-%s.json" % STAMP)
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    md_path = os.path.join(OUT_DIR, "coverage-matrix-%s.md" % STAMP)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("# API 覆盖矩阵（%s）\n\n" % result["generatedAt"])
        f.write("- 真实服务：%s\n- 性能 Harness：%s\n\n" % (SRV, HAR))
        f.write("| 路由 | 说明 | 覆盖 | 检查项 | 状态码 |\n|---|---|---|---|---|\n")
        by_route = {}
        for row in ROWS:
            if row["route"]:
                by_route.setdefault(row["route"], []).append(row)
        for kind, route, desc in catalog:
            rows = by_route.get(route, [])
            mark = "✅" if any(x["ok"] for x in rows) else "❌"
            detail = "<br>".join("%s %s (%sms)" % (x["id"], x["status"], x["ms"]) for x in rows) or "-"
            f.write("| `%s` | %s | %s | %s | %s |\n" %
                    (route, desc, mark, detail,
                     ",".join(str(x["status"]) for x in rows) or "-"))
        f.write("\n## 明细（含契约/错误路径）\n\n")
        f.write("| ID | 分组 | 端点 | 方法 | 状态 | 耗时(ms) | OK | 备注 |\n|---|---|---|---|---|---|---|---|\n")
        for row in ROWS:
            f.write("| %s | %s | `%s` | %s | %s | %s | %s | %s |\n" %
                    (row["id"], row["group"], row["path"], row["method"], row["status"],
                     row["ms"], "✅" if row["ok"] else "❌",
                     (row["note"] or "").replace("|", "/")))

    print("\n=== 覆盖：catalog %d/%d，检查 %d 项，失败 %d 项 ===" %
          (len(catalog) - len(missing), len(catalog), len(ROWS), len(failed)))
    if missing:
        for kind, route, desc in missing:
            print("[MISSING] %-8s %s (%s)" % (kind, route, desc))
    if failed:
        for row in failed:
            print("[FAILED ] %s %s %s -> %s %s | %s" %
                  (row["id"], row["method"], row["path"], row["status"], row["error"] or "",
                   row["snippet"][:160]))
    print("[out] %s" % json_path)
    print("[out] %s" % md_path)

    return 0 if not missing and not failed else 1


if __name__ == "__main__":
    sys.exit(main())

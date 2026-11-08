#!/usr/bin/env python3
# 活体冒烟：验证「分析项」参数贯通 /report 链路 —— 不同 category 生成的 AI 报告内容应可区分。
# 依赖：新编译的 java_core/out + toolchain Zulu21；用 MockAiAnalyzer（无 API Key）。
# 用法：先起 server（见注释），再跑本脚本。断言失败时退出码非 0。
import json
import pathlib
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:18800"
# 脚本所在 tests_smoke → java_core → bempdiff，夹具路径据此推导
_PROTO = pathlib.Path(__file__).resolve().parents[2]
OLD = str(_PROTO / "dist_input/_smoke/olddir")
NEW = str(_PROTO / "dist_input/_smoke/newdir")


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8")


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return r.read().decode("utf-8")


def main():
    # 1) 提交 folder 比对并轮询 DONE
    resp = post("/api/session/compare", {"leftType": "folder", "leftPath": OLD, "rightPath": NEW})
    job_id = resp.get("jobId") if isinstance(resp, dict) else json.loads(resp).get("jobId")
    if not job_id:
        print("FAIL: 无 jobId:", resp)
        return 1
    for _ in range(120):
        s = json.loads(get(f"/api/job/{job_id}/status"))
        if s.get("status") == "DONE":
            break
        if s.get("status") in ("ERROR", "CANCELLED"):
            print("FAIL: 比对失败", s)
            return 1
        time.sleep(0.4)
    else:
        print("FAIL: 比对超时")
        return 1
    print("比对 DONE, jobId=", job_id)

    # 2) 同一 job 分别用不同分析项生成 AI 报告
    reports = {}
    for cat in ["breaking", "testpoints", "impact", "risk", None]:
        body = {"ai": True}
        if cat:
            body["category"] = cat
        md = post(f"/api/job/{job_id}/report", body)
        reports[cat or "default"] = md
        tag = next((ln for ln in md.splitlines() if "本次分析聚焦" in ln), "(无聚焦标注!)")
        print(f"[{cat or 'default'}] 聚焦标注: {tag.strip()} | 长度={len(md)}")

    # 3) 断言
    keys = ["breaking", "testpoints", "impact", "risk"]
    fails = []
    for k in keys:
        if "本次分析聚焦" not in reports[k]:
            fails.append(f"{k}: 报告缺「本次分析聚焦」标注")
        if reports[k] == reports["default"]:
            fails.append(f"{k}: 报告与默认分析完全相同（参数未生效）")
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            if reports[keys[i]] == reports[keys[j]]:
                fails.append(f"{keys[i]} 与 {keys[j]} 报告内容完全相同（分析项未区分）")
    if "整体风险分析" not in reports["default"]:
        fails.append("default: 默认报告应标注「整体风险分析」")
    if fails:
        print("FAIL:", *fails, sep="\n  - ")
        return 1
    print("PASS: 不同分析项生成的报告内容可区分，且均含聚焦标注（5/5 断言通过）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

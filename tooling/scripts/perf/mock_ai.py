#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 OpenAI 兼容 mock 上游（test-only）。

仅实现 BempDiff HttpAiAnalyzer 需要的两个接口：
  GET  /v1/models              -> OpenAI data[].id 列表
  POST /v1/chat/completions    -> choices[0].message.content（内容为可被 StageA/StageB 解析的 JSON）

固定延迟由 --latency-ms 控制，用于把「网络/上游耗时」与「SUT 调度/解析耗时」分离；
请求明细落 requests.jsonl，便于区分 SUT 侧变慢还是 mock 侧变慢。
"""
import argparse
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTENT = json.dumps({
    "overallRisk": "MEDIUM",
    "impactScope": "mock 上游：影响 2 个模块，无破坏性 API 变更",
    "testThemes": ["回归核心比对流程", "验证导出资产可下载"],
    "fileRisks": [{"key": "mock/Entry.class", "risk": "LOW", "oneLineReason": "mock 初评"}],
    "conclusion": {
        "compatibilityVerdict": ["兼容"],
        "affectedModules": ["core"],
        "testPoints": [{"scenario": "mock 回归", "expect": "差异树可展开"}],
        "riskRationale": ["mock 理由"],
    },
    "intent": "mock 改动意图",
    "risk": "LOW",
    "impact": "mock 影响范围",
    "testPoints": ["mock 测试要点"],
    "contextInfluence": "mock 上下文影响",
}, ensure_ascii=False)

LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    latency = 0.03
    log_path = ""

    def log_message(self, fmt, *args):
        return

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _record(self, path, nbytes, elapsed):
        if not self.log_path:
            return
        row = {"ts": round(time.time(), 3), "path": path, "bytes": nbytes, "elapsed_ms": round(elapsed * 1000, 1)}
        with LOCK, open(self.log_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    def do_GET(self):
        t0 = time.perf_counter()
        if self.path.startswith("/v1/models") or self.path.startswith("/api/tags"):
            time.sleep(self.latency)
            self._send_json(200, {"object": "list", "data": [{"id": "mock-model", "object": "model"}]})
        else:
            self._send_json(404, {"error": "not found"})
        self._record(self.path, 0, time.perf_counter() - t0)

    def do_POST(self):
        t0 = time.perf_counter()
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not self.path.startswith("/v1/chat/completions"):
            self._send_json(404, {"error": "not found"})
            self._record(self.path, length, time.perf_counter() - t0)
            return
        time.sleep(self.latency)
        self._send_json(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": "mock-model",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": CONTENT}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": max(1, len(raw) // 4), "completion_tokens": max(1, len(CONTENT) // 4), "total_tokens": 1},
        })
        self._record(self.path, length, time.perf_counter() - t0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18091)
    ap.add_argument("--latency-ms", type=float, default=30)
    ap.add_argument("--log", default="")
    a = ap.parse_args()
    Handler.latency = max(0.0, a.latency_ms / 1000.0)
    Handler.log_path = a.log
    if a.log:
        os.makedirs(os.path.dirname(a.log) or ".", exist_ok=True)
        open(a.log, "a", encoding="utf-8").close()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    print("[mock-ai] listening on http://127.0.0.1:%d latency=%.0fms" % (a.port, a.latency_ms), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

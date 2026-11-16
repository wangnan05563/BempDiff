#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""活体冒烟：验证 /api/ai/models 按 API Base URL + Key 自动拉取模型列表。

自包含、确定性：起一个本地 mock OpenAI 兼容 /models 服务，再起真实 BempServer，
POST /api/ai/models 验证返回 {ok:true, models:[...]} 且含 mock 模型；并验证
鉴权失败(401)路径返回 ok:false + lastError。
"""
import http.server
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
JAVA = os.path.join(ROOT, "bempdiff", "toolchain", "zulu21.52.15-ca-jdk21.0.12-win_x64", "bin", "java.exe")
OUT = os.path.join(ROOT, "release", "java_core", "out")
CFR = os.path.join(ROOT, "bempdiff", "cfr.jar")


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        # 路径形如 /v1/models 或 /v1/models?...
        path = self.path.split("?")[0].rstrip("/")
        if path.endswith("/models"):
            if self.headers.get("Authorization") == "Bearer bad-key":
                self.send_response(401)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":"invalid api key"}')
                return
            body = json.dumps({"data": [
                {"id": "mock-gpt-4o"},
                {"id": "mock-deepseek-v4-flash"},
                {"id": "mock-qwen-plus"},
            ]}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()


def start_mock(port):
    srv = http.server.HTTPServer(("127.0.0.1", port), Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv


def post_models(bemp_port, base_url, api_key):
    url = "http://127.0.0.1:%d/api/ai/models" % bemp_port
    data = json.dumps({
        "provider": "custom",
        "baseUrl": base_url,
        "apiKey": api_key,
        "blockPrivateEndpoints": False,
    }).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def wait_ready(bemp_port, timeout=40):
    url = "http://127.0.0.1:%d/" % bemp_port
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.5)
    return False


def main():
    mock_port = free_port()
    bemp_port = free_port()
    srv = start_mock(mock_port)
    print("[mock] models server on 127.0.0.1:%d" % mock_port)

    tmp_home = os.path.join(ROOT, "bempdiff", "java_core", "tests_smoke", "_aismoke_home_%d" % os.getpid())
    os.makedirs(tmp_home, exist_ok=True)
    env = dict(os.environ)
    env["user.home"] = tmp_home  # 隔离配置，避免读取到真实 aiApiKey
    # 关键：server TEMP 必须是全路径，避免 8.3 短路径（C:\PYFIX_~4）破坏反编译器临时文件读
    full_tmp = os.path.join(ROOT, "bempdiff", "java_core", "tests_smoke", "_tmp_%d" % os.getpid())
    os.makedirs(full_tmp, exist_ok=True)
    env["TEMP"] = full_tmp
    env["TMP"] = full_tmp
    env["TMPDIR"] = full_tmp

    proc = subprocess.Popen(
        [JAVA, "-Duser.home=" + tmp_home, "-cp", OUT + ";" + CFR,
         "com.bempdiff.Main", "server", "--port", str(bemp_port)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True)

    ok = True
    try:
        if not wait_ready(bemp_port):
            print("[FAIL] BempServer 未在限时内就绪")
            ok = False
        else:
            base = "http://127.0.0.1:%d/v1" % mock_port
            # 1) 正常拉取（空 key 视为本地模型）
            r = post_models(bemp_port, base, "")
            print("[resp] ok=%s models=%s lastError=%s" % (r.get("ok"), r.get("models"), r.get("lastError")))
            if not (r.get("ok") and isinstance(r.get("models"), list)
                    and "mock-gpt-4o" in r["models"] and len(r["models"]) == 3):
                print("[FAIL] 正常拉取未返回 3 个 mock 模型")
                ok = False
            else:
                print("[ok] 正常拉取返回 3 个模型: %s" % r["models"])

            # 2) 鉴权失败(401)路径
            r2 = post_models(bemp_port, base, "bad-key")
            print("[resp] 401 ok=%s lastError=%s" % (r2.get("ok"), r2.get("lastError")))
            if not (r2.get("ok") is False and r2.get("lastError") and "401" in r2["lastError"]):
                print("[FAIL] 401 路径未正确返回失败原因")
                ok = False
            else:
                print("[ok] 401 路径正确返回失败原因")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()
        srv.shutdown()

    # 清理临时目录
    for d in (tmp_home, full_tmp):
        try:
            import shutil
            shutil.rmtree(d, ignore_errors=True)
        except Exception:
            pass

    if ok:
        print("\n=== AI 模型列表自动获取 活体冒烟全部通过 ===")
        sys.exit(0)
    else:
        print("\n=== AI 模型列表自动获取 活体冒烟存在失败 ===")
        sys.exit(1)


if __name__ == "__main__":
    main()

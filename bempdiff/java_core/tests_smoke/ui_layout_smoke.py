#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""启动真实 BempServer 并托管已构建的 webui/dist，供浏览器(layout)冒烟验证使用。

- 复用 Zulu21 + cfr.jar，隔离 user.home 与全路径 TEMP（规避 8.3 短路径坑）。
- 通过 --webroot 指向 vite 构建产物，使 / 返回真实 SPA 而非占位页。
- 阻塞运行：检测到 stop 文件即退出（便于外部脚本在验证结束后清理）。
"""
import os
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
JAVA = os.path.join(ROOT, "bempdiff", "toolchain", "zulu21.52.15-ca-jdk21.0.12-win_x64", "bin", "java.exe")
OUT = os.path.join(ROOT, "release", "java_core", "out")
CFR = os.path.join(ROOT, "bempdiff", "cfr.jar")
DIST = os.path.join(ROOT, "release", "webui", "dist")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18799
STOP = os.path.join(ROOT, "bempdiff", "java_core", "tests_smoke", "_uismoke_stop_%d" % PORT)
TMPH = os.path.join(ROOT, "bempdiff", "java_core", "tests_smoke", "_uismoke_home_%d" % PORT)
FULLT = os.path.join(ROOT, "bempdiff", "java_core", "tests_smoke", "_uismoke_tmp_%d" % PORT)


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_ready(timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/" % PORT, timeout=3) as r:
                if r.status == 200 and b'<div id="app">' in r.read():
                    return True
        except Exception:
            time.sleep(0.5)
    return False


def main():
    if os.path.exists(STOP):
        os.remove(STOP)
    for d in (TMPH, FULLT):
        os.makedirs(d, exist_ok=True)
    env = dict(os.environ)
    env["user.home"] = TMPH
    env["TEMP"] = FULLT
    env["TMP"] = FULLT
    env["TMPDIR"] = FULLT

    proc = subprocess.Popen(
        [JAVA, "-Duser.home=" + TMPH, "-cp", OUT + ";" + CFR,
         "com.bempdiff.Main", "server", "--port", str(PORT), "--webroot", DIST],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, encoding="utf-8", errors="replace")

    # 后台线程把子进程输出透传，方便排错
    def pump():
        for line in proc.stdout:
            sys.stdout.write("[server] " + line)
    import threading
    threading.Thread(target=pump, daemon=True).start()

    if not wait_ready():
        print("[FAIL] server 未在限时内就绪 (SPA 未托管?)", flush=True)
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()
        sys.exit(2)

    print("[ok] server ready on http://127.0.0.1:%d/  (SPA 已托管)" % PORT, flush=True)
    print("[info] 验证完成后写入 stop 文件以退出: %s" % STOP, flush=True)

    # 阻塞直到 stop 文件出现
    while not os.path.exists(STOP):
        if proc.poll() is not None:
            print("[FAIL] server 进程意外退出 rc=%s" % proc.returncode, flush=True)
            sys.exit(3)
        time.sleep(0.5)

    print("[info] 收到 stop 信号，关闭 server", flush=True)
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except Exception:
        proc.kill()


if __name__ == "__main__":
    main()

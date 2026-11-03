#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""性能测试环境编排（隔离起停 + 健康检查 + AI 配置）。

用法：
  python tooling/scripts/perf/perf_env.py build          # 编译 harness（需要时）
  python tooling/scripts/perf/perf_env.py start          # 起 mock_ai / harness / server
  python tooling/scripts/perf/perf_env.py status
  python tooling/scripts/perf/perf_env.py stop           # 仅按 pid 文件定向关停

安全：绝不按映像名杀进程；只关停本脚本写出的 pid（taskkill /PID ... /T /F）。
隔离：每个 JVM 独立 -Duser.home 与 -Djava.io.tmpdir，不读写真实用户目录。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CFG_PATH = os.path.join(ROOT, "config", "perf-env.yml")


def load_cfg():
    import yaml
    with open(CFG_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f)


def p(value):
    if value is None or value == "":
        return value
    if os.path.isabs(value) or (os.sep not in value and "/" not in value and not value.startswith(".")):
        return value  # 可执行命令名（如 python）保持原样，交给 PATH
    return os.path.normpath(os.path.join(ROOT, value))


def env_dirs(cfg):
    root = p(cfg["env_root"])
    return {
        "root": root,
        "pids": os.path.join(root, "pids"),
        "logs": os.path.join(root, "logs"),
        "home_server": p(cfg["server"]["user_home"]),
        "tmp_server": p(cfg["server"]["tmp_dir"]),
        "home_harness": p(cfg["harness"]["user_home"]),
        "tmp_harness": p(cfg["harness"]["tmp_dir"]),
    }


def ensure_dirs(cfg):
    for d in env_dirs(cfg).values():
        if d:
            os.makedirs(d, exist_ok=True)


def http_get(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read()
    except Exception as e:
        return None, str(e).encode()


def http_put_json(url, obj, timeout=10):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def wait_health(name, url, retries=90, interval=1.0):
    for i in range(retries):
        code, body = http_get(url)
        if code and 200 <= code < 500:
            print("[env] %s ready (%s): %s" % (name, url, body[:120].decode("utf-8", "ignore")))
            return True
        time.sleep(interval)
    print("[env] %s NOT ready: %s" % (name, url))
    return False


def listening_pids(port):
    """netstat 解析本地监听 pid（只读，用于安全校验）。"""
    try:
        out = subprocess.check_output(["netstat", "-ano", "-p", "TCP"], text=True, encoding="mbcs", errors="ignore")
    except Exception:
        return []
    pids = []
    needle = ":%d " % port
    for line in out.splitlines():
        if "LISTENING" in line and needle in line:
            parts = line.split()
            if parts:
                try:
                    pids.append(int(parts[-1]))
                except ValueError:
                    pass
    return sorted(set(pids))


def start_proc(name, cmd, cwd, log_path, pid_path):
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    log = open(log_path, "ab", buffering=0)
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, creationflags=0x00000008)  # DETACHED_PROCESS
    with open(pid_path, "w", encoding="utf-8") as f:
        f.write(str(proc.pid))
    print("[env] started %s pid=%d log=%s" % (name, proc.pid, log_path))
    return proc.pid


def stop_pid(name, pid_path):
    if not os.path.isfile(pid_path):
        print("[env] no pid file for %s" % name)
        return
    try:
        pid = int(open(pid_path, encoding="utf-8").read().strip())
    except Exception:
        print("[env] invalid pid file for %s" % name)
        return
    try:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        print("[env] stopped %s pid=%d" % (name, pid))
    except Exception as e:
        print("[env] stop %s failed: %s" % (name, e))
    finally:
        try:
            os.remove(pid_path)
        except OSError:
            pass


def cmd_build(cfg):
    javac = p(cfg["javac_exe"])
    core_out = p(cfg["harness"]["classes_core"])
    harness_out = p(cfg["harness"]["classes_harness"])
    cfr = p(cfg["harness"]["cfr_jar"])
    dep = p(cfg["harness"]["dep_lib"])
    cp = cfr + os.pathsep + os.path.join(dep, "*")
    os.makedirs(core_out, exist_ok=True)
    os.makedirs(harness_out, exist_ok=True)
    core_src = []
    for base, _, files in os.walk(os.path.join(ROOT, "bempdiff", "java_core", "src")):
        core_src += [os.path.join(base, f) for f in files if f.endswith(".java")]
    har_src = []
    for base, _, files in os.walk(os.path.join(ROOT, "bempdiff", "perf-harness", "src")):
        har_src += [os.path.join(base, f) for f in files if f.endswith(".java")]
    for label, src, out, cp0 in [
        ("core", core_src, core_out, cp),
        ("harness", har_src, harness_out, core_out + os.pathsep + cp),
    ]:
        print("[env] javac %s (%d files)" % (label, len(src)))
        r = subprocess.run([javac, "-encoding", "UTF-8", "-cp", cp0, "-d", out] + src,
                           cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                           encoding="utf-8", errors="ignore")
        if r.returncode != 0:
            print(r.stdout)
            raise SystemExit("javac %s failed rc=%d" % (label, r.returncode))
    print("[env] build ok")


def cmd_start(cfg):
    ensure_dirs(cfg)
    d = env_dirs(cfg)
    java = p(cfg["java_exe"])
    mock = cfg["mock_ai"]
    srv = cfg["server"]
    har = cfg["harness"]

    targets = [
        ("mock_ai", mock["port"], os.path.join(d["pids"], "mock_ai.pid")),
        ("harness", har["port"], os.path.join(d["pids"], "harness.pid")),
        ("server", srv["port"], os.path.join(d["pids"], "server.pid")),
    ]
    for name, port, pidfile in targets:
        if os.path.isfile(pidfile):
            print("[env] %s pid file exists, skipping start (stop first)" % name)
            continue
        busy = listening_pids(port)
        if busy:
            raise SystemExit("[env] port %d already in use by pid(s) %s; refusing to start" % (port, busy))

    if not os.path.isfile(os.path.join(d["pids"], "mock_ai.pid")):
        start_proc("mock_ai",
                   [p(cfg["python_exe"]), p("tooling/scripts/perf/mock_ai.py"),
                    "--port", str(mock["port"]), "--latency-ms", str(mock["latency_ms"]),
                    "--log", os.path.join(d["logs"], "mock_ai_requests.jsonl")],
                   ROOT, os.path.join(d["logs"], "mock_ai.log"), os.path.join(d["pids"], "mock_ai.pid"))
        if not wait_health("mock_ai", "http://127.0.0.1:%d/v1/models" % mock["port"]):
            raise SystemExit("mock_ai failed")

    if not os.path.isfile(os.path.join(d["pids"], "harness.pid")):
        cp = os.pathsep.join([p(har["classes_harness"]), p(har["classes_core"]), p(har["cfr_jar"]),
                              os.path.join(p(har["dep_lib"]), "*")])
        cmd = [java, *har["jvm_args"], "-Duser.home=" + d["home_harness"],
               "-Djava.io.tmpdir=" + d["tmp_harness"], "-Dbempdiff.cfr=" + p(har["cfr_jar"]),
               "-cp", cp, "com.bempdiff.perf.Harness", str(har["port"])]
        start_proc("harness", cmd, ROOT, os.path.join(d["logs"], "harness.log"),
                   os.path.join(d["pids"], "harness.pid"))
        if not wait_health("harness", "http://127.0.0.1:%d/health" % har["port"]):
            raise SystemExit("harness failed")

    if not os.path.isfile(os.path.join(d["pids"], "server.pid")):
        cp = os.pathsep.join([p(srv["app_jar"]), os.path.join(p(srv["app_lib"]), "*"), p(srv["cfr_jar"])])
        srv_java = p(srv.get("java_exe") or java)
        cmd = [srv_java, *srv["jvm_args"], "-Duser.home=" + d["home_server"],
               "-Djava.io.tmpdir=" + d["tmp_server"], "-cp", cp, "com.bempdiff.Main",
               "server", "--port", str(srv["port"]), "--webroot", p(srv["webroot"])]
        start_proc("server", cmd, ROOT, os.path.join(d["logs"], "server.log"),
                   os.path.join(d["pids"], "server.pid"))
        if not wait_health("server", "http://127.0.0.1:%d/api/config" % srv["port"]):
            raise SystemExit("server failed")

    # 配置 AI 指向本地 mock；同时把 cfrJar 配到隔离 app 副本（进程内 CFR 仍在 classpath）。
    ai = dict(cfg.get("ai_config") or {})
    ai["cfrJar"] = p(srv["cfr_jar"])
    ai["projectContextDir"] = p(cfg["fixtures"]["project_context_dir"])
    code, body = http_put_json("http://127.0.0.1:%d/api/config" % srv["port"], ai)
    print("[env] ai config PUT %s: %s" % (code, body[:160].decode("utf-8", "ignore")))
    cmd_status(cfg)


def cmd_status(cfg):
    d = env_dirs(cfg)
    srv = cfg["server"]
    har = cfg["harness"]
    mock = cfg["mock_ai"]
    if not cfg.get("_quiet"):
        for name, port in (("mock_ai", mock["port"]), ("harness", har["port"]), ("server", srv["port"])):
            pidfile = os.path.join(d["pids"], name + ".pid")
            pid = open(pidfile, encoding="utf-8").read().strip() if os.path.isfile(pidfile) else "-"
            print("[env] %-8s port=%d pid=%s listening=%s" % (name, port, pid, listening_pids(port)))
    for name, url in (("mock_ai", "http://127.0.0.1:%d/v1/models" % mock["port"]),
                      ("harness", "http://127.0.0.1:%d/health" % har["port"]),
                      ("server", "http://127.0.0.1:%d/api/config" % srv["port"])):
        code, body = http_get(url)
        print("[env] %-8s %s -> %s %s" % (name, url, code, body[:100].decode("utf-8", "ignore")))


def cmd_stop(cfg):
    d = env_dirs(cfg)
    for name in ("server", "harness", "mock_ai"):
        stop_pid(name, os.path.join(d["pids"], name + ".pid"))
    cmd_status(cfg)


def main():
    cfg = load_cfg()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "build":
        cmd_build(cfg)
    elif cmd == "start":
        cmd_start(cfg)
    elif cmd == "stop":
        cmd_stop(cfg)
    elif cmd == "status":
        cmd_status(cfg)
    else:
        raise SystemExit("unknown command: " + cmd)


if __name__ == "__main__":
    main()

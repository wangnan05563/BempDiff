# -*- coding: utf-8 -*-
"""B1 修复的语义检查：cleanup 不吃新鲜文件、不动 bmddiag- 子目录；诊断包下发后无残留。"""
import json
import os
import shutil
import subprocess
import time
import urllib.request

# probe → perf-2026-11-01 → results → jmeter → tooling → 项目根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
ENV = r"E:\qoder-perf\env"
LOG = os.path.join(ENV, "logs")
JAVA_SRV = os.path.join(ROOT, r"bempdiff\dist_input\jre\bin\java.exe")
PORT = 18090
BASE = "http://127.0.0.1:%d" % PORT
HOME = os.path.join(ENV, "home", "server")
TMP = os.path.join(ENV, "tmp", "server")


def req(method, path, timeout=30):
    r = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def wait(secs=90):
    t0 = time.time()
    while time.time() - t0 < secs:
        try:
            req("GET", "/api/config", 2)
            return True
        except Exception:
            time.sleep(1)
    return False


cp = os.pathsep.join([os.path.join(ENV, "classes", "core"),
                      os.path.join(ROOT, r"bempdiff\dist_input\app\lib\*"),
                      os.path.join(ROOT, r"bempdiff\dist_input\app\cfr.jar")])
logf = open(os.path.join(LOG, "b1-cleanup-semantics.log"), "ab", buffering=0)
proc = subprocess.Popen([JAVA_SRV, "-Xms512m", "-Xmx3g", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8",
                         "-Duser.home=" + HOME, "-Djava.io.tmpdir=" + TMP, "-cp", cp,
                         "com.bempdiff.Main", "server", "--port", str(PORT),
                         "--webroot", os.path.join(ROOT, r"bempdiff\webui\dist")],
                        cwd=ROOT, stdout=logf, stderr=subprocess.STDOUT,
                        stdin=subprocess.DEVNULL, creationflags=0x00000008)
try:
    assert wait(), "server not ready"
    old = os.path.join(TMP, "bempdiff-old.tmp")
    fresh = os.path.join(TMP, "bempdiff-fresh.tmp")
    sim = os.path.join(TMP, "bmddiag-sim")
    os.makedirs(sim, exist_ok=True)
    with open(os.path.join(sim, "bempdiff-diag-sim.zip"), "wb") as f:
        f.write(b"x" * 1024)
    for p in (old, fresh):
        with open(p, "wb") as f:
            f.write(b"y" * 4096)
    past = time.time() - 120
    os.utime(old, (past, past))

    code1, body1 = req("POST", "/api/admin/cleanup")
    time.sleep(2)
    code2, body2 = req("POST", "/api/admin/cleanup")
    diag_code, diag_body_lines = 200, None
    t0 = time.time()
    with urllib.request.urlopen(BASE + "/api/admin/diag-bundle", timeout=30) as r:
        diag_code = r.status
        diag_body_lines = len(r.read())
    diag_ms = round((time.time() - t0) * 1000)
    after_first = {"old": os.path.exists(old), "fresh": os.path.exists(fresh),
                   "bmddiag_sim": os.path.isdir(sim)}
    time.sleep(31)
    code3, body3 = req("POST", "/api/admin/cleanup")
    after_wait = {"old": os.path.exists(old), "fresh": os.path.exists(fresh),
                  "bmddiag_sim": os.path.isdir(sim)}
    leftovers = [e for e in os.listdir(TMP) if e.startswith("bmddiag-") and e != "bmddiag-sim"]
    print(json.dumps({
        "cleanup1": [code1, body1], "cleanup2": [code2, body2],
        "after_two_cleanups": after_first,
        "after_age_and_cleanup3": [code3, body3], "state3": after_wait,
        "diag": {"code": diag_code, "bytes": diag_body_lines, "ms": diag_ms},
        "tmp_leftover_bmddiag": leftovers,
    }, ensure_ascii=False, indent=1))
finally:
    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    logf.close()
    shutil.rmtree(sim, ignore_errors=True)
    for p in (os.path.join(TMP, "bempdiff-fresh.tmp"),):
        if os.path.exists(p):
            os.remove(p)

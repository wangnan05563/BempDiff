# -*- coding: utf-8 -*-
"""B1 复验：同一台隔离 server，分别用「未修复 jar」与「修复后 classes」跑 srv_adminops。

只按 pid 定向关停；生产 18765 不参与（启动前断言端口空闲）。
用法: python b1_verify.py <variant: jar|classes> <threads> <duration> <tag>
"""
import csv
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

# probe → perf-2026-11-01 → results → jmeter → tooling → 项目根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
ENV = r"E:\qoder-perf\env"
JTL = r"E:\qoder-perf\jtl\b1"
LOG = os.path.join(ENV, "logs")
JAVA_SRV = os.path.join(ROOT, r"bempdiff\dist_input\jre\bin\java.exe")
JAVA_JM = os.path.join(ROOT, r"bempdiff\toolchain\zulu21.52.15-ca-jdk21.0.12-win_x64\bin\java.exe")
JMETER = r"D:\code\Jmeter\apache-jmeter-5.6.3\bin\ApacheJMeter.jar"
JMX = os.path.join(ROOT, r"tooling\jmeter\load\srv_adminops.jmx")
PORT = 18090


def listening(port):
    out = subprocess.check_output(["netstat", "-ano", "-p", "TCP"], text=True,
                                  encoding="mbcs", errors="ignore")
    pids = []
    for line in out.splitlines():
        if "LISTENING" in line and (":%d " % port) in line:
            pids.append(line.split()[-1])
    return sorted(set(pids))


def wait_http(url, secs=90):
    t0 = time.time()
    while time.time() - t0 < secs:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                return r.status
        except Exception:
            time.sleep(1)
    return None


def main():
    variant, threads, duration, tag = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    assert not listening(PORT), "port %d busy: %s" % (PORT, listening(PORT))
    assert not listening(18765), "production 18765 must stay untouched"
    os.makedirs(LOG, exist_ok=True)
    os.makedirs(JTL, exist_ok=True)
    home = os.path.join(ENV, "home", "server")
    tmp = os.path.join(ENV, "tmp", "server")
    if variant == "jar":
        cp = os.pathsep.join([os.path.join(ROOT, r"bempdiff\dist_input\app\bempdiff.jar"),
                              os.path.join(ROOT, r"bempdiff\dist_input\app\lib\*"),
                              os.path.join(ROOT, r"bempdiff\dist_input\app\cfr.jar")])
    else:
        cp = os.pathsep.join([os.path.join(ENV, "classes", "core"),
                              os.path.join(ROOT, r"bempdiff\dist_input\app\lib\*"),
                              os.path.join(ROOT, r"bempdiff\dist_input\app\cfr.jar")])
    # 清理上一轮遗留的临时产物，保证每轮起点一致
    if os.path.isdir(tmp):
        for e in os.listdir(tmp):
            p = os.path.join(tmp, e)
            shutil.rmtree(p, ignore_errors=True) if os.path.isdir(p) else os.remove(p)
    os.makedirs(tmp, exist_ok=True)
    os.makedirs(home, exist_ok=True)

    slog = os.path.join(LOG, "b1-%s.log" % variant)
    with open(slog, "w", encoding="utf-8") as f:
        f.write("variant=%s threads=%d duration=%d\n" % (variant, threads, duration))
    logf = open(slog, "ab", buffering=0)
    cmd = [JAVA_SRV, "-Xms512m", "-Xmx3g", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8",
           "-Duser.home=" + home, "-Djava.io.tmpdir=" + tmp, "-cp", cp,
           "com.bempdiff.Main", "server", "--port", str(PORT),
           "--webroot", os.path.join(ROOT, r"bempdiff\webui\dist")]
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=logf, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, creationflags=0x00000008)
    code = wait_http("http://127.0.0.1:%d/api/config" % PORT)
    print("[srv] variant=%s pid=%d health=%s" % (variant, proc.pid, code), flush=True)
    if code is None:
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        raise SystemExit("server failed to start, see " + slog)

    jtl = os.path.join(JTL, "%s-%s-t%d.jtl" % (tag, variant, threads))
    if os.path.exists(jtl):
        os.remove(jtl)
    jlog = os.path.join(LOG, "b1-jmeter-%s-%s-t%d.log" % (tag, variant, threads))
    env = dict(os.environ, JAVA_HOME=os.path.dirname(os.path.dirname(JAVA_JM)))
    with open(jlog, "w", encoding="utf-8") as jf:
        rc = subprocess.run([JAVA_JM, "-jar", JMETER, "-n", "-t", JMX, "-l", jtl,
                             "-Jport=%d" % PORT, "-Jthreads=%d" % threads,
                             "-Jduration=%d" % duration, "-Jramp=5"],
                            cwd=ROOT, env=env, stdout=jf, stderr=subprocess.STDOUT,
                            timeout=duration + 300).returncode
    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    logf.close()

    rows, labels = [], {}
    with open(jtl, encoding="utf-8", errors="ignore") as f:
        for r in csv.DictReader(f):
            try:
                el = int(r["elapsed"])
            except Exception:
                continue
            ok = r.get("success", "").lower() == "true"
            rows.append(el)
            L = labels.setdefault(r["label"], {"n": 0, "err": 0, "el": [], "codes": {}})
            L["n"] += 1
            L["el"].append(el)
            L["codes"][r["responseCode"]] = L["codes"].get(r["responseCode"], 0) + 1
            if not ok:
                L["err"] += 1
    rows.sort()
    errs = sum(v["err"] for v in labels.values())
    out = {
        "variant": variant, "threads": threads, "duration": duration, "rc": rc,
        "samples": len(rows), "errors": errs,
        "error_rate": round(errs * 100.0 / len(rows), 4) if rows else None,
        "avg": round(sum(rows) / len(rows), 1) if rows else None,
        "p50": rows[int(len(rows) * .50)] if rows else None,
        "p95": rows[int(len(rows) * .95)] if rows else None,
        "p99": rows[int(len(rows) * .99)] if rows else None,
        "max": rows[-1] if rows else None,
        "labels": {k: {"n": v["n"], "err": v["err"], "max": max(v["el"]) if v["el"] else None,
                       "avg": round(sum(v["el"]) / len(v["el"]), 1) if v["el"] else None,
                       "codes": v["codes"]} for k, v in labels.items()},
        "tmp_after": sorted(os.listdir(tmp)),
    }
    print(json.dumps(out, ensure_ascii=False, indent=1), flush=True)
    dst = os.path.join(JTL, "%s-%s-t%d.json" % (tag, variant, threads))
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("[jtl] " + jtl, flush=True)


if __name__ == "__main__":
    main()

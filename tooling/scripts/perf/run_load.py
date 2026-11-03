#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BempDiff 基线 / 压力阶梯 / 4h Soak 编排（test-only）。

职责：
  1. seed 真实 API 需要的状态（jobSmall / jobNested / jobFolder / jobBig）；
  2. 调原生 JMeter 跑 gen_load_jmx.py 生成的 JMX（-J 传 jobId / 端口 / 线程数）；
  3. 并行采样被测进程资源（monitor_sut.py，按 pid 文件精确定位）；
  4. 聚合 JTL -> summary.json / summary.md（错误率、TPS、p50/p90/p95/p99、资源峰值）。

用法：
  python tooling/scripts/perf/run_load.py --phase baseline --duration 60
  python tooling/scripts/perf/run_load.py --phase stress --levels 1,2,4,8,16,32 --duration 60
  python tooling/scripts/perf/run_load.py --phase soak --soak-hours 4
"""
import argparse
import csv
import datetime as dt
import gzip
import http.client
import json
import math
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse

import yaml

try:
    import psutil
except Exception:
    psutil = None

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CFG_PATH = os.path.join(ROOT, "config", "perf-env.yml")
JMX_DIR = os.path.join(ROOT, "tooling", "jmeter", "load")
RESULTS = os.path.join(ROOT, "tooling", "jmeter", "results", "perf-2026-11-01", "load")
MONITOR = os.path.join(os.path.expanduser("~"), ".qoder-cn", "skills",
                       "jmeter-performance-test", "scripts", "monitor_sut.py")


def load_cfg():
    with open(CFG_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f)


CFG = load_cfg()
ENV_ROOT = os.path.join(ROOT, CFG["env_root"]) if not os.path.isabs(CFG["env_root"]) else CFG["env_root"]
# 大块写入（明文 JTL）落 jtl_scratch（E: 盘），仓库内只保留同名 .jtl.gz 归档：
# D: 已 100% 满，阶梯/Soak 的明文 JTL 单档就有几十 MB，写在系统盘上既跑不完也会拖垮其它进程。
_JTL_SCRATCH = CFG.get("jtl_scratch")
JTL_SCRATCH = (os.path.join(ROOT, _JTL_SCRATCH) if _JTL_SCRATCH and not os.path.isabs(_JTL_SCRATCH)
               else _JTL_SCRATCH) or os.path.join(ROOT, "tooling", "jmeter", "results", "perf-2026-11-01", "load")
PIDS = os.path.join(ENV_ROOT, "pids")
LOGS = os.path.join(ENV_ROOT, "logs")
JAVA = os.path.join(ROOT, CFG["java_exe"])
JMETER = CFG["jmeter_jar"]
SRV = "http://127.0.0.1:%d" % CFG["server"]["port"]
HAR = "http://127.0.0.1:%d" % CFG["harness"]["port"]


class Client:
    def __init__(self, base):
        self.base = base

    def req(self, method, path, body=None, timeout=600):
        u = urllib.parse.urlparse(self.base)
        conn = http.client.HTTPConnection(u.hostname, u.port, timeout=timeout)
        data = None
        headers = {}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        t0 = time.perf_counter()
        try:
            conn.request(method, path, body=data, headers=headers)
            resp = conn.getresponse()
            raw = resp.read()
            status = resp.status
            text = raw.decode("utf-8", "replace")
        except Exception as e:
            return {"status": 0, "text": "", "error": str(e),
                    "ms": round((time.perf_counter() - t0) * 1000, 1)}
        finally:
            conn.close()
        return {"status": status, "text": text, "error": None,
                "ms": round((time.perf_counter() - t0) * 1000, 1)}


def jbody(r):
    try:
        return json.loads(r["text"]) if r["text"] else None
    except Exception:
        return None


def fixtures(name):
    left, right = CFG["fixtures"][name].split(",")

    def p(v):
        v = v.strip()
        return v if os.path.isabs(v) else os.path.normpath(os.path.join(ROOT, v))
    return p(left), p(right)


def start_compare(c, left, right, left_type="package", options=None):
    body = {"leftType": left_type, "leftPath": left, "rightPath": right}
    if options is not None:
        body["options"] = options
    return c.req("POST", "/api/session/compare", body=body, timeout=120)


def wait_job(c, job_id, timeout=1800, interval=0.5):
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout:
        r = c.req("GET", "/api/job/%s/status" % job_id, timeout=120)
        j = jbody(r)
        if j:
            last = j
        if (j or {}).get("status") in ("DONE", "FAILED", "CANCELLED"):
            return j, time.time() - t0
        time.sleep(interval)
    return last, time.time() - t0


def first_key(tree, pred):
    for n in tree or []:
        try:
            if pred(n):
                return n.get("key")
        except Exception:
            pass
    return None


def seed_job(c, kind):
    """按类型 seed 一个作业。

    服务端 sweepSupersededJobs 只保留「最近结束的 1 个终态作业」的快照，
    故每类作业必须在其计划组开始前单独 seed，且组内不得再提交新比对。
    """
    if kind == "small":
        small_l, small_r = fixtures("small")
        r = start_compare(c, small_l, small_r, options={"expandAll": True, "topK": 12})
        job = (jbody(r) or {}).get("jobId")
        if not job:
            raise SystemExit("[seed] 小包 compare 失败: %s %s" % (r["status"], r["text"][:200]))
        st, secs = wait_job(c, job)
        if (st or {}).get("status") != "DONE":
            raise SystemExit("[seed] 小包任务未完成: %s" % (st or {}).get("status"))
        tree = (st or {}).get("tree") or []
        key = first_key(tree, lambda n: n.get("status") == "MODIFIED") or first_key(tree, lambda n: True)
        print("[seed] small job=%s key=%s %.1fs" % (job, key, secs), flush=True)
        return {"jobSmall": job, "keySmall": key or ""}
    if kind == "nested":
        nested_l, nested_r = fixtures("nested_zip")
        r = start_compare(c, nested_l, nested_r, options={"expandAll": True, "topK": 12})
        job = (jbody(r) or {}).get("jobId")
        if not job:
            raise SystemExit("[seed] 嵌套 compare 失败: %s" % r["text"][:200])
        st, secs = wait_job(c, job)
        tree = (st or {}).get("tree") or []
        key = first_key(tree, lambda n: n.get("fileClass") in ("JAR", "ARCHIVE")
                        and not str(n.get("key", "")).endswith("/"))
        print("[seed] nested job=%s key=%s %.1fs" % (job, key, secs), flush=True)
        return {"jobNested": job, "keyNested": key or ""}
    if kind == "folder":
        folder_l, folder_r = fixtures("folder")
        r = start_compare(c, folder_l, folder_r, left_type="folder",
                          options={"expandAll": True, "topK": 12})
        job = (jbody(r) or {}).get("jobId")
        if not job:
            raise SystemExit("[seed] 文件夹 compare 失败: %s" % r["text"][:200])
        st, secs = wait_job(c, job)
        tree = (st or {}).get("tree") or []
        key = first_key(tree, lambda n: n.get("status") == "MODIFIED") or first_key(tree, lambda n: True)
        print("[seed] folder job=%s key=%s %.1fs" % (job, key, secs), flush=True)
        return {"jobFolder": job, "keyFolder": key or ""}
    if kind == "big":
        big_l, big_r = fixtures("big200")
        r = start_compare(c, big_l, big_r, options={"expandAll": False, "topK": 12})
        job = (jbody(r) or {}).get("jobId")
        if not job:
            raise SystemExit("[seed] big200 compare 失败: %s" % r["text"][:200])
        st, secs = wait_job(c, job)
        if (st or {}).get("status") != "DONE":
            raise SystemExit("[seed] big200 未完成: %s" % (st or {}).get("status"))
        print("[seed] big200 job=%s %.1fs" % (job, secs), flush=True)
        return {"jobBig": job}
    if kind == "folder_ops":
        # rename/delete 会消耗夹具文件，故每次从 pristine 的 folders 重拷一份再比对。
        src = [fixtures("folder")[0], fixtures("folder")[1]]
        dst = [fixtures("folder_ops")[0], fixtures("folder_ops")[1]]
        for s, d in zip(src, dst):
            if os.path.isdir(d):
                shutil.rmtree(d)
            shutil.copytree(s, d)
        r = start_compare(c, dst[0], dst[1], left_type="folder",
                          options={"expandAll": True, "topK": 12})
        job = (jbody(r) or {}).get("jobId")
        if not job:
            raise SystemExit("[seed] folder_ops compare 失败: %s %s" % (r["status"], r["text"][:200]))
        st, secs = wait_job(c, job)
        tree = (st or {}).get("tree") or []
        key = first_key(tree, lambda n: n.get("status") == "MODIFIED") or first_key(tree, lambda n: True)
        print("[seed] folder_ops job=%s key=%s %.1fs" % (job, key, secs), flush=True)
        return {"jobFolder": job, "keyFolder": key or ""}
    if kind == "export":
        # 大包异步导出（>256MB）：一次比对 seed 两个导出记录，
        # exportId 给 detail/download，exportId2 给 delete（delete 不可逆，故只出现在单发计划里）。
        large_l, large_r = fixtures("largebytes")
        for f in (large_l, large_r):
            if not os.path.isfile(f):
                raise SystemExit("[seed] 缺少大包导出夹具 %s，先跑 gen_export_large_fixture.py" % f)
        r = start_compare(c, large_l, large_r, options={"expandAll": False, "topK": 12})
        job = (jbody(r) or {}).get("jobId")
        if not job:
            raise SystemExit("[seed] largebytes compare 失败: %s %s" % (r["status"], r["text"][:200]))
        st, secs = wait_job(c, job, timeout=1800)
        if (st or {}).get("status") != "DONE":
            raise SystemExit("[seed] largebytes 未完成: %s" % (st or {}).get("status"))
        ids = []
        for _ in range(2):
            e = c.req("POST", "/api/job/%s/export/start" % job, body={"granularity": "all"}, timeout=120)
            j = jbody(e) or {}
            if e["status"] != 202 or not j.get("id"):
                raise SystemExit("[seed] 异步导出未受理: %s %s" % (e["status"], e["text"][:200]))
            ids.append(j["id"])
        wait_exports(c, ids)
        print("[seed] export job=%s ids=%s" % (job, ",".join(ids)), flush=True)
        return {"exportJob": job, "exportId": ids[0], "exportId2": ids[1]}
    if kind is None:
        return {}
    raise SystemExit("[seed] 未知类型 %s" % kind)


def wait_exports(c, ids, cap_sec=900, interval=2):
    """轮询 /api/export/list 直到这批记录都不是 running；超时即中止（否则后面下载必拿半包）。"""
    t0 = time.time()
    while time.time() - t0 < cap_sec:
        r = c.req("GET", "/api/export/list", timeout=60)
        lst = jbody(r) or []
        by = {x.get("id"): x.get("status") for x in lst if isinstance(x, dict)}
        pending = [i for i in ids if by.get(i) == "running" or i not in by]
        failed = [i for i in ids if by.get(i) == "error"]
        if failed:
            raise SystemExit("[seed] 异步导出失败: %s" % failed)
        if not pending:
            return round(time.time() - t0, 1)
        time.sleep(interval)
    raise SystemExit("[seed] 异步导出 %ds 未就绪: %s" % (cap_sec, ids))


# (计划, 需要的 seed 类型, SUT)
GROUPS_SERVER = [
    ("srv_session.jmx", "small", "server"),
    ("srv_export.jmx", None, "server"),      # 复用上一个 seed(small) 的作业
    ("srv_archive.jmx", "nested", "server"),
    ("srv_file.jmx", "folder", "server"),
    ("srv_config.jmx", None, "server"),
]
# 写侧（提交比对作业）单独成组并限档：一次提交即 enqueue 一个真实比对作业，
# 而消费上限是 min(8, cores)，高档位只会制造数万份作业积压 —— 那测的是队列而非 API。
# 真实业务并发远低于此（用户提交后必须等结果），故阶梯上限取 --submit-max-threads（默认 8）。
GROUPS_SUBMIT = [
    ("srv_compare.jmx", None, "server"),
]
# 管理操作组（diag-bundle 生成 zip + cleanup 全量回收临时文件）：两者互相清理，
# 并发跑必然出现「A 的 zip 被 B 的 cleanup 删掉」，属产品级竞态；
# 真实业务并发≈1，故阶梯只到 --admin-max-threads（默认 4），且采样超时收紧到 60s。
GROUPS_ADMIN = [
    ("srv_adminops.jmx", None, "server"),
]
GROUPS_HEAVY = [
    ("srv_read_big.jmx", "big", "server"),
    ("srv_ai.jmx", "small", "server"),
]
# 补充轮：覆盖「破坏性 / 外部依赖」端点，这些不能进常规无限循环阶梯（详见 gen_mutate_jmx.py）。
# folder_ops 每次重拷夹具，故 copy/cancel 等可重复写侧允许进阶梯。
GROUPS_SUPPLEMENT = [
    ("srv_mutate.jmx", "folder_ops", "server"),
]


def ensure_job(c, job_id):
    if not job_id:
        return False
    r = c.req("GET", "/api/job/%s/status" % job_id, timeout=30)
    return r["status"] == 200


# ------------------------------------------------------------------ monitor
def write_monitor_cfg(name, pid_file, out_csv):
    path = os.path.join(ENV_ROOT, "monitor-%s.yml" % name)
    cfg = {
        "resource_monitor": {
            "enabled": True,
            "match": {"mode": "pid_file", "pid_file": pid_file, "name": "", "cmd_substr": ""},
            "sample_interval_sec": 2,
            "duration_samples": 100000,
            "metrics": ["rss_mb", "private_mb", "cpu_percent", "handles", "threads"],
            "output_csv": out_csv,
            "alert": {"enabled": True, "max_rss_mb": 3072, "on_exceed": "warn"},
        }
    }
    with open(path, "w", encoding="utf-8") as f:
        yaml.safe_dump(cfg, f, allow_unicode=True)
    return path


class Monitors:
    def __init__(self, tag):
        self.procs = []
        self.csv = {}
        for name in ("server", "harness"):
            pid_file = os.path.join(PIDS, name + ".pid")
            out = os.path.join(ENV_ROOT, "resource-%s-%s.csv" % (tag, name))
            cfg = write_monitor_cfg("%s-%s" % (tag, name), pid_file, out)
            log = open(os.path.join(LOGS, "monitor-%s-%s.log" % (tag, name)), "w", encoding="utf-8")
            p = subprocess.Popen([sys.executable, MONITOR, "--config", cfg],
                                 cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            self.procs.append((p, log))
            self.csv[name] = out
        time.sleep(1.5)

    def stop(self):
        for p, log in self.procs:
            try:
                p.terminate()
                p.wait(timeout=10)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass
            log.close()


# ------------------------------------------------------------------ jvm probe
class JvmProbe:
    """按 pid 直读平台线程数 / RSS / 句柄数。

    monitor_sut.py 的 threads 指标在本机取不到值，而「4 小时是否泄漏线程/句柄」正是
    Soak 的主判据之一，故用 psutil 独立采样，输出 load/soak/jvm-probe.json 供分析。
    """

    def __init__(self, tag, interval=15):
        self.tag = tag
        self.interval = interval
        self.rows = []
        self._stop = threading.Event()
        self._thread = None
        self.out = os.path.join(RESULTS, tag, "jvm-probe.json")

    def _pids(self):
        out = {}
        for name in ("server", "harness"):
            pf = os.path.join(PIDS, name + ".pid")
            if os.path.isfile(pf):
                with open(pf, encoding="utf-8") as f:
                    txt = f.read().strip()
                if txt.isdigit():
                    out[name] = int(txt)
        return out

    def start(self):
        if psutil is None:
            print("[probe] psutil 不可用，跳过线程/句柄采样", flush=True)
            return self
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        return self

    def _run(self):
        procs = {}
        t0 = time.time()
        while not self._stop.is_set():
            for name, pid in self._pids().items():
                if procs.get(name) is None or procs[name].pid != pid:
                    try:
                        procs[name] = psutil.Process(pid)
                    except Exception:
                        continue
                try:
                    p = procs[name]
                    with p.oneshot():
                        self.rows.append({"ts": round(time.time() - t0, 1), "name": name,
                                          "pid": pid, "threads": p.num_threads(),
                                          "handles": p.num_handles(),
                                          "rss_mb": round(p.memory_info().rss / 2 ** 20, 1)})
                except Exception:
                    procs[name] = None
            # 周期性落盘：Soak 崩了也要留下已采到的泄漏曲线
            if len(self.rows) % 16 == 0:
                self.dump()
            self._stop.wait(self.interval)

    def summarize(self):
        by = {}
        for r in self.rows:
            by.setdefault(r["name"], []).append(r)
        summary = {}
        for name, rows in by.items():
            first, last = rows[0], rows[-1]
            hours = max(0.001, (last["ts"] - first["ts"]) / 3600.0)
            summary[name] = {
                "samples": len(rows), "span_hours": round(hours, 2),
                "threads_first": first["threads"], "threads_last": last["threads"],
                "threads_max": max(r["threads"] for r in rows),
                "handles_first": first["handles"], "handles_last": last["handles"],
                "handles_max": max(r["handles"] for r in rows),
                "rss_first_mb": first["rss_mb"], "rss_last_mb": last["rss_mb"],
                "rss_max_mb": max(r["rss_mb"] for r in rows),
                "rss_growth_mb_per_hour": round((last["rss_mb"] - first["rss_mb"]) / hours, 1),
                "threads_growth_per_hour": round((last["threads"] - first["threads"]) / hours, 2),
            }
        return summary

    def dump(self):
        if not self.rows:
            return None
        os.makedirs(os.path.dirname(self.out), exist_ok=True)
        tmp = self.out + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"tag": self.tag, "summary": self.summarize(), "rows": self.rows}, f,
                      ensure_ascii=False)
        os.replace(tmp, self.out)
        return self.summarize()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=30)
        summary = self.dump()
        if summary:
            print("[probe] %s -> %s" % (self.tag, self.out), flush=True)
        return summary


# ------------------------------------------------------------------ jmeter
LAST_NOTE = [None]


def free_mb():
    return shutil.disk_usage(ROOT).free // (1024 * 1024)


def run_plan(tag, jmx_name, port, threads, duration, ramp=5, props=None, timeout_extra=240,
             jtl_suffix=None):
    out_dir = os.path.join(JTL_SCRATCH, tag)
    os.makedirs(out_dir, exist_ok=True)
    free = shutil.disk_usage(out_dir).free // (1024 * 1024)
    if free < 300:
        raise DiskGuard("[disk] JTL 暂存盘 %s 剩余 %dMB < 300MB，停止压测以免污染结果" % (out_dir, free))
    # 每档并发独立落盘：JMeter 的 -l 是追加写，同名复用会把上一档样本混进这一档；
    # 阶梯跑很长，任何一档卡死也不能覆盖其它档已采到的数据。
    # 同一计划同线程数跑多轮（如导出记录的 loops 差异）时，再用 jtl_suffix 区分，
    # 否则后一轮的 os.remove + 归档覆盖会吃掉前一轮的原始样本。
    jtl = os.path.join(out_dir, "%s-t%d%s.jtl" % (jmx_name.replace(".jmx", ""), threads,
                                                  ("-" + jtl_suffix) if jtl_suffix else ""))
    if os.path.exists(jtl):
        os.remove(jtl)
    jmx = os.path.join(JMX_DIR, jmx_name)
    cmd = [JAVA, "-jar", JMETER, "-n", "-t", jmx, "-l", jtl,
           "-Jport=%d" % port, "-Jthreads=%d" % threads,
           "-Jduration=%d" % duration, "-Jramp=%d" % ramp]
    for k, v in (props or {}).items():
        cmd.append("-J%s=%s" % (k, v))
    env = dict(os.environ)
    env["JAVA_HOME"] = os.path.dirname(os.path.dirname(JAVA))
    log_path = os.path.join(LOGS, "jmeter-%s-%s-t%d%s.log"
                            % (tag, jmx_name.replace(".jmx", ""), threads,
                               ("-" + jtl_suffix) if jtl_suffix else ""))
    t0 = time.time()
    note = None
    with open(log_path, "w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            # 只按本进程 pid 定向关停（严禁按映像名 taskkill /IM java.exe）
            rc = proc.wait(timeout=duration + timeout_extra)
        except subprocess.TimeoutExpired:
            proc.kill()
            rc = proc.wait()
            note = "stalled: 超过 %ds 未收尾，按 pid 定向关停，保留已采样本" % (duration + timeout_extra)
            print("[jmeter] !! %s t=%d 卡顿超时，已定向关停 pid=%s" % (jmx_name, threads, proc.pid), flush=True)
    wall = time.time() - t0
    LAST_NOTE[0] = note
    print("[jmeter] %-28s t=%-3d rc=%d wall=%.0fs -> %s"
          % (jmx_name, threads, rc, wall, jtl), flush=True)
    return jtl, rc, wall


# ------------------------------------------------------------------ aggregate
class DiskGuard(Exception):
    """磁盘余量不足：跳过后续档位，但已完成档位的结论必须照常出。"""


def archive_jtl(jtl, tag, keep=None):
    """明文 JTL 留在暂存盘（E:）便于复算，仓库内只收 .jtl.gz 归档，保证交付可追溯又不占系统盘。

    归档失败（多为系统盘 D: 写满）不能中断压测：原始样本仍在暂存盘，
    只把失败记在 summary 里，收尾时人工搬运或清理后补归档。

    `keep` 用于同名多次归档的场景（Soak 逐周期复用同一 JMX/线程数，
    不加周期号会逐周期覆盖，只剩最后一轮原始样本）。
    """
    out_dir = os.path.join(RESULTS, tag)
    try:
        os.makedirs(out_dir, exist_ok=True)
        gz = os.path.join(out_dir, (keep or os.path.basename(jtl)) + ".gz")
        with open(jtl, "rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
            shutil.copyfileobj(src, dst)
        return gz
    except OSError as e:
        print("[archive] !! %s 归档失败（%s），保留暂存盘原始样本 %s"
              % (os.path.basename(jtl), e, jtl), flush=True)
        return None


def record(runs, plan, threads, jtl, rc, wall, tag=None):
    s = summarize(jtl)
    s["note"] = LAST_NOTE[0]
    s["rc"] = rc
    s["wall_sec"] = round(wall, 1)
    runs.append({"plan": plan, "threads": threads, "rc": rc, "wall": wall,
                 "jtl": archive_jtl(jtl, tag) if tag else jtl,
                 "jtl_scratch": jtl, "summary": s})
    return runs[-1]["summary"]


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    idx = max(0, min(len(s) - 1, int(math.ceil(p / 100.0 * len(s))) - 1))
    return s[idx]


def read_jtl(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace", newline="") as f:
        for row in csv.DictReader(f):
            try:
                rows.append({
                    "ts": int(row["timeStamp"]),
                    "elapsed": int(row["elapsed"]),
                    "label": row["label"],
                    "code": row["responseCode"],
                    "bytes": int(row.get("bytes") or 0),
                })
            except Exception:
                continue
    return rows


def summarize(path):
    rows = read_jtl(path)
    if not rows:
        return {"requests": 0, "note": "empty jtl", "labels": {}}
    errs = [r for r in rows if not (r["code"].startswith("2") or r["code"].startswith("3"))]
    lat = [r["elapsed"] for r in rows]
    span = max(r["ts"] + r["elapsed"] for r in rows) - min(r["ts"] for r in rows)
    span = max(span, 1) / 1000.0
    by = {}
    for r in rows:
        d = by.setdefault(r["label"], {"n": 0, "err": 0, "lat": [], "bytes": 0, "codes": {}})
        d["n"] += 1
        d["lat"].append(r["elapsed"])
        d["bytes"] += r["bytes"]
        d["codes"][r["code"]] = d["codes"].get(r["code"], 0) + 1
        if not (r["code"].startswith("2") or r["code"].startswith("3")):
            d["err"] += 1
    labels = {}
    for k, d in sorted(by.items()):
        labels[k] = {
            "n": d["n"], "err": d["err"],
            "err_rate": round(100.0 * d["err"] / d["n"], 2),
            "tps": round(d["n"] / span, 2),
            "avg_ms": round(sum(d["lat"]) / len(d["lat"]), 1),
            "p50_ms": pct(d["lat"], 50), "p90_ms": pct(d["lat"], 90),
            "p95_ms": pct(d["lat"], 95), "p99_ms": pct(d["lat"], 99),
            "max_ms": max(d["lat"]), "codes": d["codes"],
            "kbps": round(d["bytes"] / 1024.0 / span, 1),
        }
    return {
        "requests": len(rows),
        "errors": len(errs),
        "error_rate": round(100.0 * len(errs) / len(rows), 2),
        "tps": round(len(rows) / span, 2),
        "span_sec": round(span, 1),
        "p50_ms": pct(lat, 50), "p90_ms": pct(lat, 90),
        "p95_ms": pct(lat, 95), "p99_ms": pct(lat, 99),
        "avg_ms": round(sum(lat) / len(lat), 1), "max_ms": max(lat),
        "labels": labels,
    }


def summarize_resources(csv_paths):
    out = {}
    for name, path in csv_paths.items():
        if not os.path.isfile(path):
            continue
        vals = {"rss": [], "priv": [], "cpu": [], "threads": [], "handles": []}
        with open(path, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                try:
                    rss = float(row.get("rss_mb") or 0)
                    if rss <= 0:
                        continue  # SUT 停止后 sidecar 仍会写 0 行，不能当作下降证据
                    vals["rss"].append(rss)
                    vals["priv"].append(float(row.get("private_mb") or 0))
                    vals["cpu"].append(float(row.get("cpu_percent") or 0))
                    vals["threads"].append(float(row.get("threads") or 0))
                    vals["handles"].append(float(row.get("handles") or 0))
                except Exception:
                    continue
        if not vals["rss"]:
            continue
        out[name] = {
            "samples": len(vals["rss"]),
            "rss_max_mb": round(max(vals["rss"]), 1),
            "rss_avg_mb": round(sum(vals["rss"]) / len(vals["rss"]), 1),
            "private_max_mb": round(max(vals["priv"]), 1),
            "cpu_avg_pct": round(sum(vals["cpu"]) / len(vals["cpu"]), 1),
            "cpu_max_pct": round(max(vals["cpu"]), 1),
            "threads_max": int(max(vals["threads"])),
            "handles_max": int(max(vals["handles"])),
        }
    return out


def gzip_jtl(path):
    gz = path + ".gz"
    with open(path, "rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
        shutil.copyfileobj(src, dst)
    os.remove(path)
    return gz


def write_phase_report(tag, runs, resources):
    out_dir = os.path.join(RESULTS, tag)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"runs": runs, "resources": resources}, f, ensure_ascii=False, indent=2)
    lines = ["# %s 汇总（%s）" % (tag, dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")), ""]
    lines.append("| 计划 | 线程 | 墙钟(s) | 请求 | TPS | 错误率 | avg | p90 | p95 | p99 | max |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in runs:
        s = r.get("summary") or {}
        lines.append("| %s | %d | %.0f | %s | %s | %s%% | %s | %s | %s | %s | %s |" % (
            r["plan"], r["threads"], r["wall"], s.get("requests", 0), s.get("tps", 0),
            s.get("error_rate", 0), s.get("avg_ms", 0), s.get("p90_ms", 0),
            s.get("p95_ms", 0), s.get("p99_ms", 0), s.get("max_ms", 0)))
    lines += ["", "## 资源峰值", "", "| SUT | 样本 | RSS max | Private max | CPU avg | CPU max | 线程 max | 句柄 max |",
              "|---|---|---|---|---|---|---|---|"]
    for k, v in resources.items():
        if not isinstance(v, dict) or "samples" not in v:
            # 队列排空耗时、磁盘熔断说明等「非资源」行也留在同一张表里，避免丢上下文
            lines.append("| _note_ %s | %s | | | | | | |" % (k, v))
            continue
        lines.append("| %s | %d | %.0fMB | %.0fMB | %.1f%% | %.1f%% | %d | %d |" % (
            k, v["samples"], v["rss_max_mb"], v["private_max_mb"],
            v["cpu_avg_pct"], v["cpu_max_pct"], v["threads_max"], v["handles_max"]))
    lines += ["", "## 端点明细", ""]
    for r in runs:
        labels = (r.get("summary") or {}).get("labels") or {}
        if not labels:
            continue
        lines.append("### %s (threads=%d)" % (r["plan"], r["threads"]))
        lines.append("")
        lines.append("| 端点 | n | 错误率 | TPS | avg | p95 | p99 | codes |")
        lines.append("|---|---|---|---|---|---|---|---|")
        for name, d in labels.items():
            lines.append("| %s | %d | %.2f%% | %.2f | %.1f | %s | %s | %s |" % (
                name, d["n"], d["err_rate"], d["tps"], d["avg_ms"],
                d["p95_ms"], d["p99_ms"], d["codes"]))
        lines.append("")
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def render_reports(tags):
    """从已落盘的 summary.json 重渲染 summary.md。

    阶梯数据（JTL + summary.json）每档即时归档，渲染器崩了不该重跑几小时负载。
    """
    for tag in tags:
        path = os.path.join(RESULTS, tag, "summary.json")
        if not os.path.isfile(path):
            print("[report] 跳过 %s（无 summary.json）" % tag, flush=True)
            continue
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        write_phase_report(tag, d.get("runs") or [], d.get("resources") or {})
        print("[report] %s -> %s" % (tag, os.path.join(RESULTS, tag, "summary.md")), flush=True)


# ------------------------------------------------------------------ phases
def _port(sut):
    return CFG["server"]["port"] if sut == "server" else CFG["harness"]["port"]


def wait_backlog(c, cap_sec=300, interval=5):
    """等比对作业队列排空（/api/tasks/running 为空）。
    排不空不报错——「排不空」本身就是结论：提交速率远超消费能力。"""
    t0 = time.time()
    residual = None
    while time.time() - t0 < cap_sec:
        body = jbody(c.req("GET", "/api/tasks/running", timeout=30))
        running = (body or {}).get("running")
        if running is None:
            return round(time.time() - t0, 1), None
        residual = len(running)
        if residual == 0:
            return round(time.time() - t0, 1), 0
        time.sleep(interval)
    return round(time.time() - t0, 1), residual


def _run_group(tag, plans, props, threads, duration, runs, timeout_extra=240):
    for jmx, kind, sut in plans:
        if kind:
            props = dict(props)
            props.update(seed_job(Client(SRV), kind))
        jtl, rc, wall = run_plan(tag, jmx, _port(sut), threads, duration,
                                 props=props, timeout_extra=timeout_extra)
        record(runs, jmx.replace(".jmx", ""), threads, jtl, rc, wall, tag)
    return props


def run_baseline(args):
    dur = args.duration
    mon = Monitors("baseline") if not args.no_monitor else None
    runs = []
    drain = {}
    try:
        c = Client(SRV)
        props = seed_job(c, "small")
        for jmx, kind, sut in GROUPS_SERVER:
            if kind:
                props = dict(props)
                props.update(seed_job(c, kind))
            jtl, rc, wall = run_plan("baseline", jmx, _port(sut), 1, dur, props=props)
            record(runs, jmx.replace(".jmx", ""), 1, jtl, rc, wall, "baseline")
        _run_group("baseline", GROUPS_SUBMIT, {}, 1, dur, runs)
        drain["baseline_submit_1t"], drain["baseline_submit_1t_residual"] = wait_backlog(c, 300)
        _run_group("baseline", GROUPS_ADMIN, {}, 1, args.admin_duration, runs, timeout_extra=180)
        drain["baseline_adminops_1t"], drain["baseline_adminops_1t_residual"] = wait_backlog(c, 60)
        _run_group("baseline", GROUPS_HEAVY, {}, 1, dur, runs)
        # Harness 无状态，单独跑（不受服务端快照回收影响）
        for jmx, dur_h in (("harness_read.jmx", dur), ("harness_parse_diff.jmx", args.heavy_duration),
                           ("harness_decompile.jmx", args.heavy_duration)):
            jtl, rc, wall = run_plan("baseline", jmx, CFG["harness"]["port"], 1, dur_h)
            record(runs, jmx.replace(".jmx", ""), 1, jtl, rc, wall, "baseline")
    finally:
        resources = {}
        if mon:
            mon.stop()
            resources = summarize_resources(mon.csv)
        resources.update(drain)
    write_phase_report("baseline", runs, resources)
    return runs


def run_stress(args):
    levels = [int(x) for x in args.levels.split(",") if x.strip()]
    # 管理操作（诊断包 / 全量清理）在真实业务里是单运维手点，且会写整包 zip、
    # 全量回收临时文件；高并发只会制造磁盘噪声，故单独限制在 --admin-max-threads 内。
    admin_levels = [x for x in levels if x <= args.admin_max_threads]
    submit_levels = [x for x in levels if x <= args.submit_max_threads]
    heavy_levels = [x for x in levels if x <= 8]
    big_levels = [x for x in levels if x <= 4]
    mon = Monitors("stress") if not args.no_monitor else None
    runs = []
    drain = {}
    stopped = None
    try:
        c = Client(SRV)
        for lvl in levels:
            try:
                _run_group("stress", GROUPS_SERVER, {}, lvl, args.duration, runs)
            except DiskGuard as e:
                stopped = str(e)
                break
            if lvl in submit_levels:
                try:
                    _run_group("stress", GROUPS_SUBMIT, {}, lvl, args.duration, runs)
                    sec, residual = wait_backlog(c, args.drain_cap)
                    drain["submit_drain_t%d" % lvl] = sec
                    drain["submit_residual_t%d" % lvl] = residual
                    print("[drain] t=%d 队列排空耗时 %.0fs，残留 %s" % (lvl, sec, residual), flush=True)
                except DiskGuard as e:
                    stopped = str(e)
                    break
        for lvl in sorted(set(admin_levels) | {1}):
            _run_group("stress", GROUPS_ADMIN, {}, lvl, args.admin_duration, runs, timeout_extra=180)
        for lvl in heavy_levels:
            if stopped:
                break
            for jmx, dur_h in (("harness_parse_diff.jmx", args.heavy_duration),
                               ("harness_decompile.jmx", args.heavy_duration),
                               ("harness_read.jmx", args.duration)):
                try:
                    jtl, rc, wall = run_plan("stress", jmx, CFG["harness"]["port"], lvl, dur_h)
                    record(runs, jmx.replace(".jmx", ""), lvl, jtl, rc, wall, "stress")
                except DiskGuard as e:
                    stopped = str(e)
                    break
        for lvl in big_levels:
            if stopped:
                break
            try:
                _run_group("stress", GROUPS_HEAVY, {}, lvl, args.heavy_duration, runs)
            except DiskGuard as e:
                stopped = str(e)
    finally:
        resources = {}
        if mon:
            mon.stop()
            resources = summarize_resources(mon.csv)
        resources.update(drain)
        if stopped:
            resources["stopped"] = stopped
    write_phase_report("stress", runs, resources)
    return runs


def run_supplement(args):
    """补充轮：把基线/压力阶梯没覆盖的破坏性与外呼端点也测出阶梯。

    顺序有讲究：srv_mutate_once 会吃掉夹具文件、删掉导出记录，必须放在最后；
    它跑完后 folders_ops 与 exportId2 已不可用，所以这两类样本每轮只取一次。
    """
    levels = [int(x) for x in args.levels.split(",") if x.strip()]
    levels = [x for x in levels if x <= args.mutate_max_threads]
    mon = Monitors("supplement") if not args.no_monitor else None
    runs = []
    stopped = None
    resources = {}

    def emit():
        write_phase_report("supplement", runs, resources)

    try:
        c = Client(SRV)
        props = seed_job(c, "small")          # cancel 目标
        for lvl in levels:
            try:
                # GROUPS_SUPPLEMENT 的 kind=folder_ops 会在每档前重拷夹具，
                # 保证 copy 阶梯用的是「未被上一档动过」的目录
                _run_group("supplement", GROUPS_SUPPLEMENT, dict(props), lvl, args.duration, runs)
                emit()
            except DiskGuard as e:
                stopped = str(e)
                break
        exp = seed_job(c, "export")           # 大包异步导出记录（202 -> done）
        for loops in (args.export_loops, 1):
            try:
                jtl, rc, wall = run_plan("supplement", "srv_export_rec.jmx", _port("server"),
                                         1, args.duration,
                                         props=dict(props, **exp, loops=loops,
                                                    thinkMs=args.think_ms),
                                         timeout_extra=1800, jtl_suffix="loops%d" % loops)
                record(runs, "srv_export_rec_loops%d" % loops, 1, jtl, rc, wall, "supplement")
                emit()
            except DiskGuard as e:
                stopped = str(e)
                break
        try:
            # rename/delete 会吃掉 C18/perf_renamed 与 exportId2，必须紧接一次重拷、且放在最后
            ops = seed_job(c, "folder_ops")
            jtl, rc, wall = run_plan("supplement", "srv_mutate_once.jmx", _port("server"), 1,
                                     args.duration,
                                     props=dict(props, **ops, **exp, loops=args.once_loops,
                                                thinkMs=args.think_ms),
                                     timeout_extra=1800)
            record(runs, "srv_mutate_once", 1, jtl, rc, wall, "supplement")
        except DiskGuard as e:
            stopped = str(e)
    finally:
        if mon:
            mon.stop()
            resources = summarize_resources(mon.csv)
        if stopped:
            resources["stopped"] = stopped
        emit()
    return runs


def run_soak(args):
    end = time.time() + args.soak_hours * 3600
    c = Client(SRV)
    os.makedirs(os.path.join(RESULTS, "soak"), exist_ok=True)
    mon = Monitors("soak") if not args.no_monitor else None
    probe = JvmProbe("soak").start()
    cycles = []

    def emit():
        with open(os.path.join(RESULTS, "soak", "cycles.json"), "w", encoding="utf-8") as f:
            json.dump({"cycles": cycles, "resources": resources}, f, ensure_ascii=False, indent=2)

    resources = {}
    try:
        while time.time() < end - 150:
            n = len(cycles) + 1
            rec = {"cycle": n, "ts": dt.datetime.now().isoformat(timespec="seconds")}
            props = seed_job(c, "small")
            read_dur = int(min(300, max(30, end - time.time() - 240)))
            jtl, rc, wall = run_plan("soak", "soak_server.jmx", CFG["server"]["port"],
                                     args.soak_threads, read_dur, props=props, timeout_extra=900)
            rec["server_read"] = _slim(summarize(jtl))
            rec["server_read_jtl"] = archive_jtl(jtl, "soak", keep=_cycle_name(jtl, n))
            remaining = end - time.time()
            if remaining < 120:
                cycles.append(rec)
                break
            jtl, rc, wall = run_plan("soak", "soak_harness.jmx", CFG["harness"]["port"],
                                     args.soak_threads, int(min(180, remaining - 90)),
                                     timeout_extra=900)
            rec["harness"] = _slim(summarize(jtl))
            rec["harness_jtl"] = archive_jtl(jtl, "soak", keep=_cycle_name(jtl, n))
            remaining = end - time.time()
            if remaining < 60:
                cycles.append(rec)
                break
            jtl, rc, wall = run_plan("soak", "soak_write.jmx", CFG["server"]["port"],
                                     args.soak_threads, int(min(120, remaining)),
                                     props={"thinkMs": args.soak_think_ms}, timeout_extra=900)
            rec["write"] = _slim(summarize(jtl))
            rec["write_jtl"] = archive_jtl(jtl, "soak", keep=_cycle_name(jtl, n))
            sec, residual = wait_backlog(c, args.drain_cap)
            rec["drain_sec"] = sec
            rec["queue_residual"] = residual
            cycles.append(rec)
            emit()
            print("[soak] cycle %d done; 剩余 %.0fmin" % (n, (end - time.time()) / 60), flush=True)
    finally:
        if mon:
            mon.stop()
            resources = summarize_resources(mon.csv)
        resources["jvm_probe"] = probe.stop()
    emit()
    write_phase_report("soak", _soak_runs(cycles, args.soak_threads), resources)
    return cycles


def _slim(summary):
    return {k: v for k, v in summary.items() if k != "labels"}


def _cycle_name(jtl, n):
    """Soak 每周期共用同一 JMX 与线程数，归档名必须带周期号，否则逐周期互相覆盖。"""
    base = os.path.basename(jtl)
    return (base[:-4] if base.endswith(".jtl") else base) + "-c%d" % n


def _soak_runs(cycles, threads):
    out = []
    for plan_key in ("server_read", "harness", "write"):
        reqs = sum(c.get(plan_key, {}).get("requests", 0) for c in cycles)
        errs = sum(c.get(plan_key, {}).get("errors", 0) for c in cycles)
        spans = sum(c.get(plan_key, {}).get("span_sec", 0) for c in cycles)
        lat = [c[plan_key]["p99_ms"] for c in cycles if c.get(plan_key, {}).get("p99_ms")]
        out.append({"plan": "soak:" + plan_key, "threads": threads, "rc": 0,
                    "wall": spans,
                    "summary": {"requests": reqs,
                                "errors": errs,
                                "error_rate": round(100.0 * errs / reqs, 3) if reqs else 0,
                                "tps": round(reqs / spans, 2) if spans else 0,
                                "p50_ms": None, "p90_ms": None, "p95_ms": None,
                                "p99_ms": max(lat) if lat else None,
                                "avg_ms": None, "max_ms": None, "labels": {}}})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", required=True,
                    choices=["baseline", "stress", "supplement", "soak", "report", "all"])
    ap.add_argument("--levels", default="1,2,4,8,16,32")
    ap.add_argument("--duration", type=int, default=60)
    ap.add_argument("--soak-hours", type=float, default=4.0)
    ap.add_argument("--soak-threads", type=int, default=4)
    ap.add_argument("--soak-think-ms", type=int, default=5000)
    ap.add_argument("--heavy-duration", type=int, default=120)
    ap.add_argument("--admin-duration", type=int, default=60)
    ap.add_argument("--admin-max-threads", type=int, default=4)
    ap.add_argument("--submit-max-threads", type=int, default=8)
    ap.add_argument("--mutate-max-threads", type=int, default=4)
    ap.add_argument("--export-loops", type=int, default=6)
    ap.add_argument("--once-loops", type=int, default=1)
    ap.add_argument("--think-ms", type=int, default=5000)
    ap.add_argument("--drain-cap", type=int, default=300)
    ap.add_argument("--no-monitor", action="store_true")
    args = ap.parse_args()

    os.makedirs(RESULTS, exist_ok=True)
    os.makedirs(LOGS, exist_ok=True)
    if args.phase == "report":
        render_reports(["baseline", "stress", "supplement", "soak"])
        return
    if args.phase in ("baseline", "all"):
        run_baseline(args)
    if args.phase in ("stress", "all"):
        run_stress(args)
    if args.phase in ("supplement", "all"):
        run_supplement(args)
    if args.phase in ("soak", "all"):
        run_soak(args)


if __name__ == "__main__":
    main()

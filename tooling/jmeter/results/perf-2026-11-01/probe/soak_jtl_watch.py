# -*- coding: utf-8 -*-
"""Soak 期间逐周期 JTL 归档旁路快照。

run_load.py 的 archive_jtl 用固定文件名，多周期会互相覆盖；本脚本只做一件事：
监听 cycles.json 的周期数，一旦 +1 就把当次三份 .jtl.gz 另存为 -cN，保证
「每次测试保留完整结果」这条约束在 Soak 上也成立。
"""
import json
import os
import shutil
import sys
import time

SOAK = r"D:\code\otherProjects\18_comparePakage\tooling\jmeter\results\perf-2026-11-01\load\soak"
CYC = os.path.join(SOAK, "cycles.json")
PLANS = ["soak_server-t4", "soak_harness-t4", "soak_write-t4"]
DEADLINE = time.time() + float(sys.argv[1]) * 60 if len(sys.argv) > 1 else time.time() + 5 * 3600

def count():
    try:
        with open(CYC, encoding="utf-8") as f:
            return len((json.load(f) or {}).get("cycles") or [])
    except (OSError, ValueError):
        return -1


seen = count()
print("[watch] 起始周期数=%d，等待下一周期落盘" % seen, flush=True)
while time.time() < DEADLINE:
    n = count()
    if n > seen:
        for p in PLANS:
            src = os.path.join(SOAK, p + ".jtl.gz")
            dst = os.path.join(SOAK, "%s-c%d.jtl.gz" % (p, n))
            if os.path.isfile(src) and not os.path.isfile(dst):
                try:
                    shutil.copy2(src, dst)
                    print("[watch] cycle %d -> %s (%d bytes)"
                          % (n, os.path.basename(dst), os.path.getsize(dst)), flush=True)
                except OSError as e:
                    print("[watch] !! cycle %d 快照失败 %s" % (n, e), flush=True)
        seen = n
    time.sleep(20)
print("[watch] 结束，共快照 %d 个周期" % seen, flush=True)

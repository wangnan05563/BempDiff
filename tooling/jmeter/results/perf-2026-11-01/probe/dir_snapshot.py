# -*- coding: utf-8 -*-
"""Soak 收尾：文件缓存目录增长的第二点采样（与 *_soak_start.json 对齐比较）。

用法：python -X utf8 dir_snapshot.py end
"""
import json
import os
import sys
import time

TAG = sys.argv[1] if len(sys.argv) > 1 else "end"
BASE = r"E:/qoder-perf/env/home/server/.bempdiff"
TMP = r"E:/qoder-perf/env/tmp"


def walk(p):
    n = b = 0
    for r, _ds, fs in os.walk(p):
        for f in fs:
            try:
                b += os.path.getsize(os.path.join(r, f))
                n += 1
            except OSError:
                pass
    return n, b


out = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "base": BASE, "subs": {}}
if os.path.isdir(BASE):
    for e in sorted(os.listdir(BASE)):
        p = os.path.join(BASE, e)
        out["subs"][e] = ({"files": walk(p)[0], "bytes": walk(p)[1]}
                          if os.path.isdir(p) else {"files": 1, "bytes": os.path.getsize(p)})
    out["total"] = list(walk(BASE))
dst = r"E:/qoder-perf/probe/dirgrowth_soak_%s.json" % TAG
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, indent=1)
print("[dir]", dst, out.get("total"))

tmp = {"ts": out["ts"]}
for name in ("server", "harness"):
    p = os.path.join(TMP, name)
    if not os.path.isdir(p):
        tmp[name] = "MISSING"
        continue
    entries = os.listdir(p)
    tmp[name] = {"top_entries": len(entries)}
    for e in entries:
        if not e.startswith("bempdiff-"):
            continue
        pre = "-".join(e.split("-")[:2])
        c = tmp[name].setdefault(pre, 0)
        tmp[name][pre] = c + 1
dst2 = r"E:/qoder-perf/probe/tmpgrowth_soak_%s.json" % TAG
with open(dst2, "w", encoding="utf-8") as f:
    json.dump(tmp, f, indent=1)
print("[tmp]", dst2, tmp)

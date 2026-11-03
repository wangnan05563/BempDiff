#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「大包异步导出」判定专用夹具（test-only）。

BempServer 的导出分流阈值是 files > 20000 或 bytes > 256MB。单条目上限
（ParseConfig.maxEntryBytes = 256MB，防止 zip bomb）决定了「单文件超阈值」不可达，
因此本夹具用 2 个 130MB 条目（合计 260MB > 256MB，且每个条目均在单条目上限内）
触发 202 异步导出分支，覆盖 /api/export/{id} 管理链路；同时避免 20000+ 小文件
在 Windows 上被 Defender 逐个扫描导致导出卡死。

用法：
  python tooling/scripts/perf/gen_export_large_fixture.py
"""
import os
import random
import sys
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
def _out_dir():
    """输出目录跟随 config/perf-env.yml 的 fixtures.largebytes（scratch 已迁到 E: 盘）。"""
    try:
        import yaml
        with open(os.path.join(ROOT, "config", "perf-env.yml"), encoding="utf-8") as f:
            cfg = yaml.safe_load(f)
        first = cfg["fixtures"]["largebytes"].split(",")[0]
        if not os.path.isabs(first):
            first = os.path.join(ROOT, first)
        return os.path.dirname(first)
    except Exception:
        return os.path.join(ROOT, ".workbuddy", "tmp", "perf", "env", "largebytes")


OUT = _out_dir()
CHUNK = 1 << 20
NCHUNKS = 130  # 每个条目 130MB（< 256MB 单条目上限）；两个条目合计 260MB > 256MB 阈值


def write_jar(path, seed):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for idx in range(2):
            block = random.Random(seed * 10 + idx).randbytes(CHUNK)
            with z.open("data/huge-%d.bin" % idx, "w") as out:
                for _ in range(NCHUNKS):
                    out.write(block)


def main():
    os.makedirs(OUT, exist_ok=True)
    v1 = os.path.join(OUT, "v1.jar")
    v2 = os.path.join(OUT, "v2.jar")
    write_jar(v1, 1)
    write_jar(v2, 2)
    print("[largebytes] v1=%d bytes v2=%d bytes (changed=%d bytes)" %
          (os.path.getsize(v1), os.path.getsize(v2), 2 * NCHUNKS * CHUNK), file=sys.stderr)


if __name__ == "__main__":
    main()

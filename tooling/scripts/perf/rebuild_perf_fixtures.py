#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BempDiff 性能夹具重建脚本（test-only）。

重建压测所需的真实字节夹具（2026-10-01 轮实测口径，T01510 固化）：
  bempdiff/lib_v1.jar    <- java_core 编译产物全部 .class（真实字节池）
  bempdiff/lib_v2.jar    <- 同池确定性替换 ~12% 条目（制造 MODIFIED 差异候选）
  bempdiff/fixtures/small_real_v{1,2}.jar   24 条（MOD=4/ADD=2/DEL=2），供 ai/report/export
  bempdiff/fixtures/big{200,500}_v{1,2}.jar ~N MB ZIP_STORED，供 parse/diff SLA

用法（仓库根目录）：
  python tooling/scripts/perf/rebuild_perf_fixtures.py [--skip-big500] [--skip-big200]

磁盘预算：big200 约 411MB、big500 约 1GB（D 盘紧张时用 --skip-big500，
对应 big500_sla.jmx 场景需另配）。压测全流程见 config/jmeter-config.yml
（jmeter-performance-test 技能）与 docs/性能测试报告-2026-10-01.md。
"""
import argparse, importlib.util, os, random, sys, zipfile

ROOT = "bempdiff/fixtures"
SRC = "bempdiff/lib_v1.jar"


def build_lib_jars():
    """lib_v1.jar 由 release/java_core/out_perf（或 out）编译产物打包；lib_v2 确定性替换 12%。"""
    entries = []
    src_dir = "release/java_core/out_perf"
    if not os.path.isdir(src_dir):
        src_dir = "release/java_core/out"
    for dp, _, fns in os.walk(src_dir):
        for fn in fns:
            if fn.endswith(".class"):
                p = os.path.join(dp, fn)
                rel = os.path.relpath(p, src_dir).replace("\\", "/")
                entries.append((rel, open(p, "rb").read()))
    assert len(entries) >= 50, "class pool too small: %d (先编译 java_core)" % len(entries)
    entries.sort()
    with zipfile.ZipFile(SRC, "w", zipfile.ZIP_STORED) as z:
        for n, b in entries:
            z.writestr(n, b)
    print("lib_v1.jar:", len(entries), "classes", os.path.getsize(SRC) // 1024, "KB", file=sys.stderr)
    random.seed(20260930)
    with zipfile.ZipFile("bempdiff/lib_v2.jar", "w", zipfile.ZIP_STORED) as z2:
        for i, (n, b) in enumerate(entries):
            z2.writestr(n, entries[(i + 37) % len(entries)][1] if random.random() < 0.12 else b)
    print("lib_v2.jar written (12% modified)", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-big500", action="store_true", help="跳过 big500（约 1GB 磁盘）")
    ap.add_argument("--skip-big200", action="store_true", help="跳过 big200（约 411MB 磁盘）")
    args = ap.parse_args()
    os.makedirs(ROOT, exist_ok=True)
    build_lib_jars()
    spec = importlib.util.spec_from_file_location("gf", os.path.join(os.path.dirname(__file__), "gen_fixtures.py"))
    gf = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gf)
    gf.build_small()
    if not args.skip_big200:
        gf.build_big(200)
    if not args.skip_big500:
        gf.build_big(500)
    print("FIXTURES DONE", file=sys.stderr)


if __name__ == "__main__":
    main()

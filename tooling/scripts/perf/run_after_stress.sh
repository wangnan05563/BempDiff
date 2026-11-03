#!/bin/bash
# 压力阶梯跑完后自动接上：大包导出夹具 -> 补充轮（破坏性/外呼端点阶梯）。
# 串成一个后台脚本以免中途被外部关停打断；每一步都落日志，便于追溯。
set -uo pipefail
cd "D:/code/otherProjects/18_comparePakage" || exit 1
LOG="E:/qoder-perf/env/logs/after-stress.log"
SUMMARY="tooling/jmeter/results/perf-2026-11-01/load/stress/summary.json"

{
  echo "[wait] $(date) 等待压力阶梯产出 $SUMMARY"
  waited=0
  while [ ! -f "$SUMMARY" ]; do
    sleep 15; waited=$((waited+15))
    if [ "$waited" -ge 5400 ]; then echo "[wait] 超时 5400s，放弃（阶梯可能崩了）"; exit 1; fi
  done
  echo "[wait] 完成，用时 ${waited}s"
  # 阶梯刚结束，留 20s 让 JMeter/服务端把残留作业排空
  sleep 20

  echo "[fixture] $(date) 生成大包异步导出夹具（260MB）"
  PYTHONIOENCODING=utf-8 python -X utf8 tooling/scripts/perf/gen_export_large_fixture.py
  echo "[fixture] rc=$?"

  echo "[supplement] $(date) 开始补充轮"
  # --once-loops 1：rename/delete/export-delete 一次就消耗掉夹具与记录，多次循环只会制造
  # 「第二次必然 400」的假错误率，故单发采样并在报告里如实标注样本量。
  PYTHONIOENCODING=utf-8 python -X utf8 tooling/scripts/perf/run_load.py \
      --phase supplement --levels 1,2,4 --duration 60 \
      --export-loops 6 --once-loops 1 --think-ms 5000
  echo "[supplement] rc=$? 结束 $(date)"
} >> "$LOG" 2>&1

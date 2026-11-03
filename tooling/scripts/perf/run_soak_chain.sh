#!/bin/bash
# 压力阶梯之后的一条链：补充轮重跑（JTL 命名修复后）-> 重启隔离环境 -> 4 小时 Soak。
# 串成一个后台脚本，避免分段被外部关停打断；每步落日志，退出码不靠通知里的汇总。
set -uo pipefail
cd "D:/code/otherProjects/18_comparePakage" || exit 1
LOG="E:/qoder-perf/env/logs/soak-chain.log"
PY="python -X utf8"

{
  echo "[supplement] $(date) 重跑补充轮（JTL 按 loops 命名，修复互相覆盖）"
  PYTHONIOENCODING=utf-8 $PY tooling/scripts/perf/run_load.py \
      --phase supplement --levels 1,2,4 --duration 60 \
      --export-loops 6 --once-loops 1 --think-ms 5000
  echo "[supplement] rc=$? $(date)"

  echo "[env] $(date) 重启隔离环境（Soak 需要干净的 JVM 起点，否则内存趋势混入阶梯残留）"
  PYTHONIOENCODING=utf-8 $PY tooling/scripts/perf/perf_env.py stop
  echo "[env] stop rc=$?"
  sleep 5
  PYTHONIOENCODING=utf-8 $PY tooling/scripts/perf/perf_env.py start
  start_rc=$?
  echo "[env] start rc=$start_rc $(date)"
  if [ "$start_rc" -ne 0 ]; then echo "[env] 启动失败，终止链路（不跑 Soak）"; exit 1; fi

  echo "[soak] $(date) 开始 4 小时 Soak（soak-threads=4，取自压力拐点）"
  PYTHONIOENCODING=utf-8 $PY tooling/scripts/perf/run_load.py \
      --phase soak --soak-hours 4 --soak-threads 4 --soak-think-ms 5000 --drain-cap 300
  echo "[soak] rc=$? 结束 $(date)"
} >> "$LOG" 2>&1

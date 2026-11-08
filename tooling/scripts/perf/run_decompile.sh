#!/bin/bash
# decompile 缩放/并发压测启动脚本（后台运行；需 harness 已在 18080 存活）
# 项目根由脚本位置推导（tooling/scripts/perf → 上三级），避免目录迁移后失效
cd "$(dirname "${BASH_SOURCE[0]}")/../../.." || exit 1
export JAVA_HOME="$(pwd)/bempdiff/toolchain/zulu21.52.15-ca-jdk21.0.12-win_x64"
JM="$JMETER_HOME/bin/jmeter.bat"
PY="C:/Users/hspcadmin/.workbuddy/binaries/python/versions/3.13.12/python.exe"

# CFR 进程数采样器（背景）
rm -f logs/cfr_procs.csv 2>/dev/null
"$PY" tooling/scripts/perf/cfr_sampler.py logs/cfr_procs.csv &
SAMPLER=$!

run() {
  local name="$1"
  echo "===== START $name $(date +%T) ====="
  "$JM" -n -t "tooling/jmeter/$name.jmx" -Jhost=localhost -Jport=18080 -l "tooling/jmeter/results/$name.jtl" > "tooling/jmeter/results/$name.log" 2>&1
  echo "===== END $name rc=$? $(date +%T) ====="
}

echo "===== DECOMPILE SCALE SWEEP (topK=80) ====="
for n in 1 2 4 8 16; do run decompile_scale_T$n; done
echo "===== DECOMPILE CONCURRENCY (topK=60) ====="
for n in 1 2 4 8; do run decompile_conc_T$n; done

kill $SAMPLER 2>/dev/null
echo "ALL DECOMPILE DONE $(date +%T)"

#!/usr/bin/env bash
# BEMP 差异比对工具 · Java 核心端口 一键演示脚本
# 用法：bash run_demo.sh   （Windows 用 Git Bash；Linux/macOS 直接 bash）
set -u

cd "$(dirname "$0")" || exit 1

# ---- 定位 JDK ----
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
elif command -v javac >/dev/null 2>&1; then
  JAVAC="javac"; JAVA="java"
else
  echo "ERROR: 找不到 javac，请设置 JAVA_HOME 或把 JDK 加入 PATH"; exit 2
fi

# ---- 资源路径（相对 java_core 目录） ----
LIBV1="../lib_v1.jar"
LIBV2="../lib_v2.jar"
CFR="../cfr.jar"
REPLAY="../java_core_ai_replay"
OUT_MD="./demo_report.md"
OUT_DIR="./demo_export"
OUT="../../release/java_core/out"    # 编译产物统一落仓库根 release/

# ---- 编译（release 内产物不存在时） ----
if [ ! -f "$OUT"/com/bempdiff/Main.class ]; then
  echo "===== 编译 ====="
  rm -rf "$OUT" && mkdir -p "$OUT"
  "$JAVAC" -encoding UTF-8 -d "$OUT" $(find src -name "*.java") || { echo "COMPILE FAILED"; exit 3; }
fi

if [ ! -f "$LIBV1" ] || [ ! -f "$LIBV2" ]; then
  echo "ERROR: 缺少真实夹具 $LIBV1 / $LIBV2（用真实两版本 jar 替换即可）"; exit 4
fi

CFR_ARG=""
[ -f "$CFR" ] && CFR_ARG="--cfr $CFR"

run(){ echo; echo "########## $1 ##########"; shift; "$JAVA" -cp "$OUT" com.bempdiff.Main "$@"; }

run "1) INSPECT 真实双版本 jar" inspect "$LIBV2"
run "2) COMPARE 全量差异"       compare "$LIBV1" "$LIBV2" --expand-all
run "3) DECOMPILE 源码级 diff"  decompile "$LIBV1" "$LIBV2" --expand-all --top-k 15 $CFR_ARG
run "4) REPORT 导出 MD"         report "$LIBV1" "$LIBV2" --expand-all --top-k 15 $CFR_ARG --out "$OUT_MD"
run "5) EXPORT 差异资产"        export "$LIBV1" "$LIBV2" --expand-all --top-k 15 $CFR_ARG --out "$OUT_DIR"
run "6) AI 两阶段（离线回放）"  ai "$LIBV1" "$LIBV2" --expand-all --top-k 15 $CFR_ARG --replay "$REPLAY"

echo
echo "===== 完成 ====="
echo "报告: $OUT_MD"
echo "资产: $OUT_DIR/"
echo "AI 回放: $REPLAY/"

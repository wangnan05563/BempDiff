#!/usr/bin/env bash
# 端到端验证：构造含前端 JS/HTML/CSS 变更 + Java 类变更的 war，跑 Main 全子命令（compare/decompile/report/export/ai）。
set -u

# 项目根由脚本位置推导（java_core → bempdiff → 项目根），避免目录迁移后失效
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVAC="$ROOT/bempdiff/toolchain/zulu21.52.15-ca-jdk21.0.12-win_x64/bin/javac.exe"
JAVA="$ROOT/bempdiff/toolchain/zulu21.52.15-ca-jdk21.0.12-win_x64/bin/java.exe"
CFR="$ROOT/bempdiff/cfr.jar"
CORE_OUT="$ROOT/bempdiff/java_core/out"
WORK="$ROOT/bempdiff/java_core/e2e_work"
mkdir -p "$WORK"

echo "==> 编译 DemoOld.java / DemoNew.java"
rm -rf "$WORK/com"
"$JAVAC" -d "$WORK" "$ROOT/bempdiff/java_core/DemoOld.java"
mv "$WORK/com/demo/Demo.class" "$WORK/Demo.class.old"
"$JAVAC" -d "$WORK" "$ROOT/bempdiff/java_core/DemoNew.java"
mv "$WORK/com/demo/Demo.class" "$WORK/Demo.class.new"
echo "demo rc: $?"

echo "==> 构造 war"
python3 "$ROOT/bempdiff/java_core/make_wars.py" "$WORK"
echo "make_wars rc: $?"

CP="$CORE_OUT;$CFR"

echo "================================== compare =================================="
"$JAVA" -cp "$CP" -Dfile.encoding=UTF-8 com.bempdiff.Main compare "$WORK/old.war" "$WORK/new.war" > "$WORK/compare.out" 2>&1
echo "compare rc: $?"

echo "================================== decompile =================================="
"$JAVA" -cp "$CP" -Dfile.encoding=UTF-8 com.bempdiff.Main decompile "$WORK/old.war" "$WORK/new.war" --top-k 20 > "$WORK/decompile.out" 2>&1
echo "decompile rc: $?"

echo "================================== report =================================="
"$JAVA" -cp "$CP" -Dfile.encoding=UTF-8 com.bempdiff.Main report "$WORK/old.war" "$WORK/new.war" --out "$WORK/report.md" --top-k 20 > "$WORK/report.out" 2>&1
echo "report rc: $?"

echo "================================== export =================================="
"$JAVA" -cp "$CP" -Dfile.encoding=UTF-8 com.bempdiff.Main export "$WORK/old.war" "$WORK/new.war" --out "$WORK/export" --top-k 20 > "$WORK/export.out" 2>&1
echo "export rc: $?"

echo "================================== ai (mock) =================================="
"$JAVA" -cp "$CP" -Dfile.encoding=UTF-8 com.bempdiff.Main ai "$WORK/old.war" "$WORK/new.war" --replay "$WORK/ai_replay" --top-k 20 > "$WORK/ai.out" 2>&1
echo "ai rc: $?"

echo "DONE"

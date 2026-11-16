#!/usr/bin/env bash
# FR-005：bempdiff-cli 命令行入口（CI 门禁，Linux/macOS）。
# 退出码语义：0=无差异 / 1=有差异(--fail-on-diff) / 2=用法或运行错误。
DIR="$(cd "$(dirname "$0")" && pwd)"
CP=""
DEPS=""
# Published fat jar first (self-contained); dev classes need CFR + toolchain libs appended.
if [ -f "$DIR/../../release/dist_input/app/bempdiff.jar" ]; then CP="$DIR/../../release/dist_input/app/bempdiff.jar"; fi
if [ -z "$CP" ] && [ -f "$DIR/../../release/java_core/out/com/bempdiff/Main.class" ]; then
  CP="$DIR/../../release/java_core/out"
  DEPS=":$DIR/../cfr.jar:$DIR/../toolchain/lib/*"
fi
[ -n "$BEMPDIFF_JAR" ] && CP="$BEMPDIFF_JAR"
if [ -z "$CP" ]; then
  echo "error: bempdiff.jar not found. Set BEMPDIFF_JAR or build java_core first."
  exit 2
fi
exec java -cp "$CP$DEPS" com.bempdiff.Main "$@"

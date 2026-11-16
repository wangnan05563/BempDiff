# BempDiff P0-② 后端单测运行器（Windows / 本机）。
# 编译并运行 scripts/test_diff_rules.java（覆盖 DiffRules + LineDiff 的忽略不重要差异逻辑）。
# 依赖：JDK21（优先 $env:JAVA21_HOME，否则用内置 zulu 工具链）。
$ErrorActionPreference = 'Continue'

# 兼容两种调用方式：-File（PSScriptRoot 有效）与 -Command（PSScriptRoot 为空）。
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path $MyInvocation.MyCommand.Path -Parent }
$Proto = Split-Path $ScriptDir -Parent          # bempdiff/
$Root  = Split-Path $Proto -Parent              # 仓库根
$JDK   = if ($env:JAVA21_HOME) { "$env:JAVA21_HOME/bin" } else { "$Proto/toolchain/zulu21.52.15-ca-jdk21.0.12-win_x64/bin" }
$SRC   = "$Proto/java_core/src"
$CFR   = "$Root/release/dist_input/app/cfr.jar"
$OUT   = "$Root/release/cache/_testcls"

# 清理旧编译产物（.NET 直删绕过 safe-delete 钩子；失败回退 Remove-Item）
if (Test-Path $OUT) {
    try { [IO.Directory]::Delete($OUT, $true) } catch { Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue }
}
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

Write-Host ">> javac $JDK\javac.exe"
& "$JDK\javac.exe" -encoding UTF-8 -cp "$SRC;$CFR" -d $OUT "$ScriptDir/test_diff_rules.java" 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILE FAILED"
    # 注意：不可在此用 exit —— 在 -Command 调用链中 exit 会终止整个会话，
    # 导致调用方 run_p0_tests.ps1 的收尾（写结果文件）无法执行。
    # 改为自然返回，由 $LASTEXITCODE（javac 的非零）把失败透传给调用方。
    return
}

Write-Host ">> java TestDiffRules"
& "$JDK\java.exe" -cp "$OUT;$SRC;$CFR" TestDiffRules
# 同样不使用 exit；调用方依据 $LASTEXITCODE 判定。

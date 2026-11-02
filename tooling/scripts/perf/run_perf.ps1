<#
.SYNOPSIS
  BempDiff 性能压测一键总控（针对新架构 server 子命令）。
.DESCRIPTION
  串联：构建产物就绪检查 →（可选）生成夹具/ JMX → 拉起 Java 后端 sidecar
  （com.bempdiff.Main server --port 18080）→ 跑 JMeter 压测 → 汇总 JTL。

  设计目标：在本机一键复现 tooling/scripts/perf/run_decompile.sh 的缩放/并发压测，
  并补齐「构建产物检查 + 夹具/JMX 生成 + server 拉起 + 收尾清理」闭环。
  所有长任务日志落盘到 logs\，不依赖 PS 面板回显。

  检查与执行分离：-DryRun 只做前置评估 + 打印计划，绝不生成夹具/构建/拉服务。
.PARAMETER Build
  若 dist_input/app、dist_input/jre 缺失，则调用 build_dist_input.ps1 装配 Java 侧。
.PARAMETER ForceBuild
  无论产物是否存在都重新装配（带 -Clean）。
.PARAMETER Port
  server 监听端口，默认 18080。
.PARAMETER Plans
  仅跑指定 jmx 基名（不含 .jmx）。默认跑 tooling/jmeter\ 下所有非 _r 主计划。
.PARAMETER SkipGenFixtures
  跳过夹具生成（假定 bempdiff/fixtures 已就绪）。
.PARAMETER SkipGenJmx
  跳过 JMX 生成（假定 tooling/jmeter\*.jmx 已就绪）。
.PARAMETER Dashboard
  每个计划额外生成 JMeter HTML 仪表盘（-e -o tooling/jmeter/results/<plan>-report）。
.PARAMETER DryRun
  仅做前置检查 + 打印执行计划，不构建 / 不生成 / 不拉起 server / 不跑 JMeter。
.PARAMETER JmeterHome
  覆盖 JMeter 主目录，默认 $env:JMETER_HOME 或 D:\code\Jmeter\apache-jmeter-5.6.3。
#>
[CmdletBinding()]
param(
  [switch]$Build,
  [switch]$ForceBuild,
  [int]$Port = 18080,
  [string[]]$Plans,
  [switch]$SkipGenFixtures,
  [switch]$SkipGenJmx,
  [switch]$Dashboard,
  [switch]$DryRun,
  [string]$JmeterHome
)

$ErrorActionPreference = 'Stop'
$root      = "D:\code\otherProjects\18_comparePakage"
$proto     = Join-Path $root "bempdiff"
$distInput = Join-Path $proto "dist_input"
$appDir    = Join-Path $distInput "app"
$jreOut    = Join-Path $distInput "jre"
$bempJar   = Join-Path $appDir "bempdiff.jar"
$cfrJar    = Join-Path $appDir "cfr.jar"
$javaExe   = Join-Path (Join-Path $jreOut "bin") "java.exe"
$webuiDir  = Join-Path $distInput "webui"
$fixtures  = Join-Path $proto "fixtures"
$jmeterDir = Join-Path $root "tooling/jmeter"
$results   = Join-Path $jmeterDir "results"
$logs      = Join-Path $root "logs"
$buildScript = Join-Path (Join-Path $proto "scripts") "build_dist_input.ps1"
$managedPy = "C:\Users\hspcadmin\.workbuddy\binaries\python\versions\3.13.12\python.exe"

if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Force -Path $logs | Out-Null }
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Force -Path $results | Out-Null }

# ---- 解析 Python ----
if ($env:PYTHON_EXE -and (Test-Path $env:PYTHON_EXE)) { $PY = $env:PYTHON_EXE }
elseif (Test-Path $managedPy) { $PY = $managedPy }
else { $PY = "python" }

# ---- 解析 JMeter ----
if (-not $JmeterHome) {
    if ($env:JMETER_HOME) { $JmeterHome = $env:JMETER_HOME }
    else { $JmeterHome = "D:\code\Jmeter\apache-jmeter-5.6.3" }
}
$jmeterBat = Join-Path (Join-Path $JmeterHome "bin") "jmeter.bat"

$errors = [System.Collections.Generic.List[string]]::new()

function Check([bool]$cond, [string]$msg) {
    if (-not $cond) { $script:errors.Add($msg); Write-Host ("  [缺失] " + $msg) -ForegroundColor Red }
    else { Write-Host ("  [OK]   " + $msg) -ForegroundColor Green }
}

# ================= 前置检查（纯评估，无副作用） =================
Write-Host "`n== 前置检查 ==" -ForegroundColor Cyan
Check (Test-Path $bempJar)  ("bempdiff.jar -> " + $bempJar)
Check (Test-Path $cfrJar)   ("cfr.jar -> " + $cfrJar)
Check (Test-Path $javaExe)  ("jlink JRE -> " + $javaExe)
Check (Test-Path $jmeterBat)("JMeter -> " + $jmeterBat)
Check (Test-Path $PY)       ("Python -> " + $PY)

# ---- 构建产物就绪判断 ----
$needBuild = (-not (Test-Path $bempJar)) -or (-not (Test-Path $cfrJar)) -or (-not (Test-Path $javaExe))
if ($ForceBuild) { $needBuild = $true }
if ($needBuild) {
    if (-not $Build -and -not $ForceBuild) {
        $errors.Add("构建产物缺失，请加 -Build 自动装配，或用 bempdiff\scripts\build_dist_input.ps1 预先构建。")
    }
}

# ---- 夹具 / JMX 就绪判断 ----
$fixProxy = Join-Path $fixtures "small_real_v1.jar"
$v1 = Join-Path $fixtures "lib_v1.jar"; $v2 = Join-Path $fixtures "lib_v2.jar"
$needFix = $false
if (-not $SkipGenFixtures) {
    if (-not (Test-Path $fixProxy) -or -not (Test-Path $v1) -or -not (Test-Path $v2)) {
        $needFix = $true
        if (-not (Test-Path $v1)) { $errors.Add("夹具源缺失：$v1（gen_fixtures.py 需要）") }
        else { Write-Host "  [将生成] 夹具缺失，运行将调用 gen_fixtures.py" -ForegroundColor Yellow }
    } else { Write-Host "  [OK]   夹具已就绪" -ForegroundColor Green }
} else { Write-Host "  [跳过] 夹具检查（SkipGenFixtures）" -ForegroundColor Gray }

$jmxProxy = Join-Path $jmeterDir "parse_diff_fast.jmx"
$needJmx = $false
if (-not $SkipGenJmx) {
    if (-not (Test-Path $jmxProxy)) { $needJmx = $true; Write-Host "  [将生成] JMX 缺失，运行将调用 gen_jmx.py" -ForegroundColor Yellow }
    else { Write-Host "  [OK]   JMX 已就绪" -ForegroundColor Green }
} else { Write-Host "  [跳过] JMX 检查（SkipGenJmx）" -ForegroundColor Gray }

# ---- 选定计划 ----
if ($Plans -and $Plans.Count -gt 0) {
    $planList = $Plans
} else {
    $planList = @(Get-ChildItem -Path $jmeterDir -Filter *.jmx -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*_r.jmx' } |
        Sort-Object Name |
        ForEach-Object { $_.BaseName })
}
Write-Host ("`n计划（共 " + $planList.Count + " 个）：") -ForegroundColor Cyan
$planList | ForEach-Object { Write-Host ("  - " + $_) }

# ================= DryRun 早退（不执行任何副作用） =================
if ($DryRun) {
    Write-Host "`n== DryRun：仅前置检查 + 计划，不构建 / 不生成 / 不拉起服务 / 不跑压测 ==" -ForegroundColor Yellow
    if ($needBuild) { Write-Host "  [将执行] 装配 Java 侧（build_dist_input.ps1 -SkipFrontend）" -ForegroundColor Yellow }
    if ($needFix)   { Write-Host "  [将执行] gen_fixtures.py" -ForegroundColor Yellow }
    if ($needJmx)   { Write-Host "  [将执行] gen_jmx.py" -ForegroundColor Yellow }
    if ($errors.Count -gt 0) {
        Write-Host ("`n前置检查未通过（" + $errors.Count + " 项）：") -ForegroundColor Red
        $errors | ForEach-Object { Write-Host ("  - " + $_) }
        exit 1
    }
    Write-Host "`n前置检查通过，计划可执行（去掉 -DryRun 正式运行）。" -ForegroundColor Green
    exit 0
}

# ================= 致命错误拦截 =================
if ($errors.Count -gt 0) {
    Write-Host ("`n前置检查未通过（" + $errors.Count + " 项），终止：") -ForegroundColor Red
    $errors | ForEach-Object { Write-Host ("  - " + $_) }
    exit 1
}

# ================= 执行：构建（如需） =================
if ($needBuild) {
    Write-Host "`n== 装配 Java 侧（build_dist_input.ps1）==" -ForegroundColor Cyan
    $buildArgs = @('-SkipFrontend')
    if ($ForceBuild) { $buildArgs = @('-Clean', '-SkipFrontend') }
    & $buildScript @buildArgs 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { Write-Host ("装配失败 rc=$LASTEXITCODE，终止。") -ForegroundColor Red; exit 1 }
}

# ================= 执行：夹具（如需） =================
if ($needFix) {
    Write-Host "`n== 生成性能夹具 gen_fixtures.py ==" -ForegroundColor Cyan
    & $PY (Join-Path $root "tooling\scripts\perf\gen_fixtures.py") 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { Write-Host ("gen_fixtures.py 失败 rc=$LASTEXITCODE，终止。") -ForegroundColor Red; exit 1 }
}

# ================= 执行：JMX（如需） =================
if ($needJmx) {
    Write-Host "`n== 生成 JMX gen_jmx.py ==" -ForegroundColor Cyan
    & $PY (Join-Path $root "tooling\scripts\perf\gen_jmx.py") 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { Write-Host ("gen_jmx.py 失败 rc=$LASTEXITCODE，终止。") -ForegroundColor Red; exit 1 }
}

# ================= 拉起 server 子命令 =================
$cp = ($bempJar + ";" + $cfrJar)
$serverArgs = @('-cp', $cp, 'com.bempdiff.Main', 'server', '--port', $Port.ToString())
if (Test-Path $webuiDir) { $serverArgs += @('--webroot', $webuiDir) }

$serverLog = Join-Path $logs "perf_server.log"
Write-Host ("`n== 拉起 server (port " + $Port + ") ==") -ForegroundColor Cyan
$serverProc = $null
try {
    $serverProc = Start-Process -FilePath $javaExe -ArgumentList $serverArgs `
        -RedirectStandardOutput $serverLog -RedirectStandardError ($serverLog + ".err") `
        -NoNewWindow -PassThru
} catch {
    Write-Host ("无法启动 server：" + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
Write-Host ("  PID=" + $serverProc.Id + "  log=" + $serverLog)

# health：TCP 探活
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
            $healthy = $true; break
        }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $healthy) {
    Write-Host "server 探活失败（30s 内未监听 $Port），详见 $serverLog" -ForegroundColor Red
    try { if ($serverProc -and -not $serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force } } catch { }
    exit 1
}
Write-Host "  server 已就绪" -ForegroundColor Green

# ================= CFR 采样器（后台） =================
$samplerLog = Join-Path $logs "cfr_procs.csv"
$samplerProc = $null
try {
    $samplerProc = Start-Process -FilePath $PY -ArgumentList @((Join-Path $root "tooling\scripts\perf\cfr_sampler.py"), $samplerLog) `
        -NoNewWindow -PassThru
    Write-Host ("CFR 采样器 PID=" + $samplerProc.Id + " -> " + $samplerLog) -ForegroundColor Cyan
} catch {
    Write-Host ("CFR 采样器启动失败（非致命）：" + $_.Exception.Message) -ForegroundColor Yellow
}

# ================= 跑压测 =================
$jtls = [System.Collections.Generic.List[string]]::new()
try {
    foreach ($plan in $planList) {
        $jmx = Join-Path $jmeterDir ($plan + ".jmx")
        if (-not (Test-Path $jmx)) { Write-Host ("  跳过（无 jmx）: " + $plan) -ForegroundColor Gray; continue }
        $jtl = Join-Path $results ($plan + ".jtl")
        $log = Join-Path $logs ("jmeter_" + $plan + ".log")
        $jargs = @('-n', '-t', $jmx, '-Jhost=localhost', "-Jport=$Port", '-l', $jtl)
        if ($Dashboard) { $jargs += @('-e', '-o', (Join-Path $results ($plan + "-report"))) }
        Write-Host ("`n===== $plan =====") -ForegroundColor Cyan
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & $jmeterBat @jargs *> $log
        $rc = $LASTEXITCODE
        $sw.Stop()
        Write-Host ("  rc=$rc  耗时=$([math]::Round($sw.Elapsed.TotalSeconds,1))s  log=$log")
        if ($rc -ne 0) { Write-Host ("  JMeter 返回非零，详见 $log") -ForegroundColor Yellow }
        else { $jtls.Add($jtl) }
    }
} finally {
    if ($samplerProc -and -not $samplerProc.HasExited) { try { Stop-Process -Id $samplerProc.Id -Force } catch { } }
    if ($serverProc -and -not $serverProc.HasExited) { try { Stop-Process -Id $serverProc.Id -Force } catch { } }
    Write-Host "`n已停止 server 与 CFR 采样器。" -ForegroundColor Gray
}

# ================= 汇总 =================
if ($jtls.Count -gt 0) {
    Write-Host "`n== 汇总 JTL ==" -ForegroundColor Cyan
    & $PY (Join-Path $root "tooling\scripts\perf\analyze_jtl.py") @jtls 2>&1 | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "`n无可用 JTL（压测可能未成功产出）。" -ForegroundColor Yellow
}

Write-Host "`n全部完成。服务日志=$serverLog；CFR 采样=$samplerLog" -ForegroundColor Green
exit 0

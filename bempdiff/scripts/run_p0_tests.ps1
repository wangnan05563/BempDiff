# BempDiff 单测运行器（Windows / 本机）。
# 顺序执行：
#   [1/4] 前端 P0 算法单测（inlineDiff / foldContext / matchSev）
#   [2/4] 前端 桌面壳桥接单测（Electron preload 桥 / 浏览器 null 兜底）
#   [3/4] 前端 多 tab 对比单测（DiffView 升级后左树多开并存）
#   [4/4] 后端 P0-② DiffRules 单测（行级 diff 忽略规则）
# 任一失败即非零退出。结果同时写入 scripts/_p0_result.txt（无控制台环境可审计）。
$ErrorActionPreference = 'Continue'

# 兼容两种调用方式：-File（PSScriptRoot 有效）与 -Command（PSScriptRoot 为空）。
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path $MyInvocation.MyCommand.Path -Parent }
$Proto = Split-Path $ScriptDir -Parent
$Node  = if ($env:NODE21_HOME) { "$env:NODE21_HOME/node.exe" } else { "node" }
$ResultFile = "$ScriptDir/_p0_result.txt"
$ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

Write-Host "=== [1/4] 前端 P0 算法单测 ==="
& $Node "$ScriptDir/test_p0_frontend.mjs"
$frontAlgo = $LASTEXITCODE

Write-Host ""
Write-Host "=== [2/4] 前端 桌面壳桥接单测 ==="
& $Node "$ScriptDir/test_desktop_picker.mjs"
$frontPick = $LASTEXITCODE

Write-Host ""
Write-Host "=== [3/4] 前端 多 tab 对比单测 ==="
& $Node "$ScriptDir/test_tab_mode.mjs"
$frontTab = $LASTEXITCODE

Write-Host ""
Write-Host "=== [4/4] 后端 P0-② DiffRules 单测 ==="
& "$ScriptDir/test_p0_backend.ps1"
$back = $LASTEXITCODE

$frontAlgoOk = ($frontAlgo -eq 0)
$frontPickOk = ($frontPick -eq 0)
$frontTabOk  = ($frontTab -eq 0)
$backOk      = ($back -eq 0)
$frontOk     = ($frontAlgoOk -and $frontPickOk -and $frontTabOk)
$overall     = ($frontOk -and $backOk)

Write-Host ""
Write-Host "==================== 结果 ===================="
Write-Host ("前端 P0 算法  : " + $(if ($frontAlgoOk) { 'PASS' } else { 'FAIL' }))
Write-Host ("前端 桥接路由 : " + $(if ($frontPickOk) { 'PASS' } else { 'FAIL' }))
Write-Host ("前端 多 tab  : " + $(if ($frontTabOk) { 'PASS' } else { 'FAIL' }))
Write-Host ("后端 DiffRules: " + $(if ($backOk) { 'PASS' } else { 'FAIL' }))
Write-Host ("总览         : " + $(if ($overall) { 'ALL PASS' } else { 'HAS FAILURE' }))

$summary = @(
    "# BempDiff 单测结果 ($ts)",
    "frontend (algo)  : $(if ($frontAlgoOk) { 'PASS' } else { 'FAIL' })  (node test_p0_frontend.mjs)",
    "frontend (bridge) : $(if ($frontPickOk) { 'PASS' } else { 'FAIL' })  (node test_desktop_picker.mjs)",
    "frontend (tabs)   : $(if ($frontTabOk) { 'PASS' } else { 'FAIL' })  (node test_tab_mode.mjs)",
    "backend (rules)   : $(if ($backOk) { 'PASS' } else { 'FAIL' })  (javac/java test_diff_rules.java)",
    "overall           : $(if ($overall) { 'ALL PASS' } else { 'HAS FAILURE' })"
) -join "`n"
Set-Content -Path $ResultFile -Value $summary -Encoding utf8

exit $(if ($overall) { 0 } else { 1 })
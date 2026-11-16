#Requires -Version 5.1
<#
.SYNOPSIS
  清理 BempDiff 全部可重复构建产物（默认目标：<仓库根>\release）。
.DESCRIPTION
  release/ 是构建产物的统一出口：dist_input、webui/dist、java_core/out|test_out、
  JaCoCo 报告、安装包与构建临时目录。删除它不会触碰源码、已入库证据
  （tooling/jmeter/results、verify_exe 等）、node_modules 与 toolchain；
  重跑构建脚本即可全部重建。

  安全约束：
  - 目标必须是 <仓库根>\release 这一精确路径（仓库根以 bempdiff\package.json 为标记）；
  - 默认只统计并打印计划，需 -Force 才真正删除；-DryRun 强制只看不动；
  - 删除只在本进程内执行（Remove-Item → .NET Directory.Delete 回退），不转交其他 shell。
.PARAMETER Force
  真正执行删除。
.PARAMETER DryRun
  只打印将删除的内容与可释放空间。
#>
[CmdletBinding()]
param(
  [switch]$Force,
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not (Test-Path -LiteralPath (Join-Path $repo 'bempdiff\package.json'))) {
  throw "仓库根校验失败：$repo 下缺少 bempdiff\package.json"
}

$target = [IO.Path]::GetFullPath((Join-Path $repo 'release'))
if ((Split-Path -Parent $target) -ne $repo -or (Split-Path -Leaf $target) -ne 'release') {
  throw "目标路径异常，拒绝操作：$target"
}

function Format-Size([double]$bytes) {
  if ($bytes -ge 1GB) { return ('{0:N2} GB' -f ($bytes / 1GB)) }
  if ($bytes -ge 1MB) { return ('{0:N2} MB' -f ($bytes / 1MB)) }
  if ($bytes -ge 1KB) { return ('{0:N2} KB' -f ($bytes / 1KB)) }
  return ('{0:N0} B' -f $bytes)
}

if (-not (Test-Path -LiteralPath $target)) {
  Write-Host "[clean-release] 无需清理：$target 不存在。"
  exit 0
}

$files = @(Get-ChildItem -LiteralPath $target -Recurse -Force -File -ErrorAction SilentlyContinue)
$size = ($files | Measure-Object -Property Length -Sum).Sum
if ($null -eq $size) { $size = 0 }
Write-Host "[clean-release] 目标：$target"
Write-Host ("[clean-release] 文件 {0} 个，合计 {1}" -f $files.Count, (Format-Size $size))
Write-Host "[clean-release] 删除范围仅 release/：源码、已入库证据、node_modules、toolchain 均不受影响。"

if ($DryRun -or -not $Force) {
  if (-not $Force) {
    Write-Host "[clean-release] 当前为演练模式（未删除）。确认执行请追加 -Force。"
  }
  exit 0
}

# 若桌面壳或 sidecar 正在使用 release 内的 JRE，先提示而非强杀进程
$locked = @(Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object {
  try { $_.Path -and $_.Path.StartsWith($target, [StringComparison]::OrdinalIgnoreCase) } catch { $false }
})
if ($locked.Count -gt 0) {
  Write-Warning ("检测到 {0} 个占用进程（PID: {1}）。请先关闭 BempDiff / 执行停止服务.bat 后重试。" -f $locked.Count, (($locked | ForEach-Object { $_.Id }) -join ','))
  exit 2
}

$sw = [Diagnostics.Stopwatch]::StartNew()
try {
  Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
} catch {
  Write-Warning "Remove-Item 失败：$($_.Exception.Message)；回退 .NET Directory.Delete"
  try {
    [IO.Directory]::Delete($target, $true)
  } catch {
    throw "清理失败，目录仍存在：$target（$($_.Exception.Message)）。请关闭占用进程后重试。"
  }
}
$sw.Stop()
if (Test-Path -LiteralPath $target) {
  throw "清理失败，目录仍存在：$target"
}
Write-Host ("[clean-release] 已删除，释放约 {0}，耗时 {1:N1}s。" -f (Format-Size $size), $sw.Elapsed.TotalSeconds)
Write-Host "[clean-release] 重建：tooling/scripts/构建打包.bat 或 bempdiff/scripts/build_dist_input.ps1"

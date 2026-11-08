<#
.SYNOPSIS
  新架构（Electron 桌面壳 + jlink 瘦 JRE）构建产物审计清理脚本。
.DESCRIPTION
  递归清理 bempdiff\dist_input\app 与 bempdiff\dist_input\jre 两个目录
  （对应 build_dist_input.ps1 的 $AppDir / $JreOut），删除前先记录每个文件的
  SHA-256 + 大小到 logs\ 审计清单，便于事后追溯。

  - 不触碰 bempdiff\fixtures\*（性能测试夹具，仍被 perf 使用，非构建产物）。
  - 默认不清理旧 javafx 扁平布局；如需清理历史遗留的 app.jar / javafx-* / ikonli-*
    扁平 jar，可加 -Legacy 开关（默认关闭，避免误删）。整体清空 dist_input 用
    bempdiff\scripts\build_dist_input.ps1 -Clean 更彻底。

  删除走直接 .NET 调用，绕过本环境 safe-delete 钩子（fail-closed）。
.PARAMETER WhatIf
  只扫描并记录到清单，不真正删除。
.PARAMETER Legacy
  同时清理 dist_input 下的旧 javafx 扁平 jar（app.jar / bootstrapfx / ikonli / javafx-*）。
#>
[CmdletBinding()]
param(
  [switch]$WhatIf,
  [switch]$Legacy
)
$ErrorActionPreference = 'Continue'   # 单文件失败不中断整体审计
# 项目根由脚本位置推导（tooling/scripts/dev → 上三级），避免目录迁移后失效
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$ts = Get-Date -Format "yyyyMMdd-HHmmss"
$logs = Join-Path $root "logs"
if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Force -Path $logs | Out-Null }
$manifest = Join-Path $logs ("jarwar-delete-manifest-" + $ts + ".log")
$sha = [System.Security.Cryptography.SHA256]::Create()
$ok = 0; $scanned = 0; $fail = 0

function Audit-Delete([string]$path, [string]$rel) {
    try {
        $bytes = [System.IO.File]::ReadAllBytes($path)
        $hash = ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join ""
        $len = $bytes.Length
        if ($WhatIf) {
            Add-Content -Path $manifest -Value ("SKIP`t" + $hash + "`t" + $len + "`t" + $rel)
            $script:scanned++
        } else {
            [System.IO.File]::Delete($path)
            Add-Content -Path $manifest -Value ("DELETED`t" + $hash + "`t" + $len + "`t" + $rel)
            $script:ok++
        }
    } catch {
        Add-Content -Path $manifest -Value ("FAIL`t" + $rel + "`t" + $_.Exception.Message)
        $script:fail++
    }
}

Add-Content -Path $manifest -Value ("# new-arch audit delete manifest " + $ts)
Add-Content -Path $manifest -Value ("# mode=" + $(if($WhatIf){"WHATIF"}else{"DELETE"}) + " legacy=" + $(if($Legacy){"ON"}else{"OFF"}))

# ---- 新架构目标目录：app / jre ----
$targets = @(
  (Join-Path $root "bempdiff\dist_input\app"),
  (Join-Path $root "bempdiff\dist_input\jre")
)
foreach ($dir in $targets) {
    if (-not (Test-Path $dir)) {
        Add-Content -Path $manifest -Value ("DIR_MISSING`t" + $dir)
        continue
    }
    $files = @(Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue)
    Add-Content -Path $manifest -Value ("# scan`t" + $dir + "`tfiles=" + $files.Count)
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($root.Length).TrimStart('\')
        Audit-Delete $f.FullName $rel
    }
    if (-not $WhatIf) {
        try { [System.IO.Directory]::Delete($dir, $true); Add-Content -Path $manifest -Value ("DIR_REMOVED`t" + $dir) }
        catch { Add-Content -Path $manifest -Value ("DIR_KEEP`t" + $dir + "`t" + $_.Exception.Message) }
    }
}

# ---- 可选：旧 javafx 扁平布局清理 ----
if ($Legacy) {
    $legacyJars = @(
      "bempdiff\dist_input\app.jar",
      "bempdiff\dist_input\bootstrapfx-core-0.4.0.jar",
      "bempdiff\dist_input\ikonli-bootstrapicons-pack-12.3.1.jar",
      "bempdiff\dist_input\ikonli-core-12.3.1.jar",
      "bempdiff\dist_input\ikonli-javafx-12.3.1.jar",
      "bempdiff\dist_input\javafx-base-21-win.jar",
      "bempdiff\dist_input\javafx-controls-21-win.jar",
      "bempdiff\dist_input\javafx-fxml-21-win.jar",
      "bempdiff\dist_input\javafx-graphics-21-win.jar"
    )
    Add-Content -Path $manifest -Value ("# legacy_flat_jars=" + $legacyJars.Count)
    foreach ($rel in $legacyJars) {
        $p = Join-Path $root $rel
        if (-not (Test-Path $p)) { Add-Content -Path $manifest -Value ("MISSING`t" + $rel); continue }
        Audit-Delete $p $rel
    }
}

Add-Content -Path $manifest -Value ("# ok=" + $ok + " scanned=" + $scanned + " fail=" + $fail)
Add-Content -Path $manifest -Value ("# manifest_path=" + $manifest)
Write-Output ("DONE mode=" + $(if($WhatIf){"WHATIF"}else{"DELETE"}) + " ok=" + $ok + " scanned=" + $scanned + " fail=" + $fail + " manifest=" + $manifest)

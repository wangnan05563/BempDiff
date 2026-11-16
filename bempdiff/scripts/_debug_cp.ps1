# 调试 classpath 构建
# 路径由脚本位置推导（scripts → bempdiff），避免目录迁移后失效
$Proto = Split-Path $PSScriptRoot -Parent
$Root  = Split-Path $Proto -Parent          # 仓库根
$Lib = Join-Path $Root 'release\dist_input\app\lib'
$Cfr = Join-Path $Root 'release\dist_input\app\cfr.jar'
$CpJars = @($Cfr) + @(Get-ChildItem -Path $Lib -Filter *.jar | Select-Object -ExpandProperty FullName)
Write-Host "CpJars count: $($CpJars.Count)"
$CpJars | ForEach-Object { Write-Host "  $_" }
$Cp = $CpJars -join [IO.Path]::PathSeparator
Write-Host "Cp length: $($Cp.Length)"
Write-Host "Cp head: $($Cp.Substring(0, [Math]::Min(200, $Cp.Length)))"

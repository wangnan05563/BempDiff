# 编译 java_core 源码到 dist_input/classes（main.js findClasspath 优先读这里）
# Task 2 完成后调用，确保后端跑最新源码。
$ErrorActionPreference = 'Continue'
# 路径由脚本位置推导（scripts → bempdiff），避免目录迁移后失效
$Proto = Split-Path $PSScriptRoot -Parent
$Jdk = Join-Path $Proto 'toolchain\zulu21.52.15-ca-jdk21.0.12-win_x64\bin'
$Src   = Join-Path $Proto 'java_core\src'
$Lib   = Join-Path $Proto 'dist_input\app\lib'
$Cfr   = Join-Path $Proto 'dist_input\app\cfr.jar'
$Out   = Join-Path $Proto 'dist_input\classes'
$Javac = Join-Path $Jdk 'javac.exe'

$CpJars = @($Cfr) + @(Get-ChildItem -Path $Lib -Filter *.jar | Select-Object -ExpandProperty FullName)
$AllCp = @($Src) + $CpJars
$Cp = [string]::Join(';', $AllCp)

if (Test-Path $Out) {
    try { [IO.Directory]::Delete($Out, $true) } catch { Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue }
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null

Write-Host ">> javac (src -> $Out)"
Write-Host "Cp head: $($Cp.Substring(0, [Math]::Min(200, $Cp.Length)))"
$SrcFiles = @(Get-ChildItem -Recurse -Filter *.java $Src | Select-Object -ExpandProperty FullName)
Write-Host "src files count: $($SrcFiles.Count)"
& $Javac -encoding UTF-8 -cp $Cp -d $Out $SrcFiles 2>&1 | Select-Object -First 30
Write-Host "exit=$LASTEXITCODE"

# 编译 java_core 并跑 ModelTest + DecompileTest（短哈希相关 + BempServer 透出源）
$ErrorActionPreference = 'Continue'
# 路径由脚本位置推导（scripts → bempdiff），避免目录迁移后失效
$Proto = Split-Path $PSScriptRoot -Parent
$Jdk = Join-Path $Proto 'toolchain\zulu21.52.15-ca-jdk21.0.12-win_x64\bin'
$Src   = Join-Path $Proto 'java_core\src'
$Test  = Join-Path $Proto 'java_core\test'
$Lib   = Join-Path $Proto 'dist_input\app\lib'
$Cfr   = Join-Path $Proto 'dist_input\app\cfr.jar'
$Out   = Join-Path $Proto 'scripts\_testcls'
$Javac = Join-Path $Jdk 'javac.exe'
$Java  = Join-Path $Jdk 'java.exe'

# 收集 classpath：cfr.jar + lib/*.jar
$CpJars = @($Cfr) + @(Get-ChildItem -Path $Lib -Filter *.jar | Select-Object -ExpandProperty FullName)
$CpSep = [IO.Path]::PathSeparator
$Cp = ($Src, $Test) + $CpJars -join $CpSep
$RunCp = ($Out, $Src, $Test) + $CpJars -join $CpSep

if (Test-Path $Out) {
    try { [IO.Directory]::Delete($Out, $true) } catch { Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue }
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null

Write-Host ">> javac (tests+core -> $Out)"
$SrcFiles = @(Get-ChildItem -Recurse -Filter *.java $Src | Select-Object -ExpandProperty FullName)
$TestFiles = @(Get-ChildItem -Recurse -Filter *.java $Test | Select-Object -ExpandProperty FullName)
& $Javac -encoding UTF-8 -cp $Cp -d $Out $SrcFiles $TestFiles 2>&1 | Select-Object -First 30
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED exit=$LASTEXITCODE"; exit $LASTEXITCODE }

Write-Host ""
Write-Host ">> java TestRunner ModelTest DecompileTest"
& $Java -cp $RunCp com.bempdiff.test.TestRunner com.bempdiff.test.ModelTest com.bempdiff.test.DecompileTest
Write-Host "exit=$LASTEXITCODE"

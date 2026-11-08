# Build java_core (src + test) and run the full TestRunner suite.
# PowerShell port of build_and_test.sh (bash is unavailable on this machine).
$ErrorActionPreference = "Stop"
# 项目根由脚本位置推导（tooling/scripts/quality → 上三级），避免目录迁移后失效
$ROOT = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
# JDK resolution: JAVA21_HOME -> JAVA_HOME -> bundled Zulu (bundled one may be incomplete/corrupted)
$jdkHome = $null
foreach ($cand in @($env:JAVA21_HOME, $env:JAVA_HOME, "$ROOT\bempdiff\toolchain\zulu21.52.15-ca-jdk21.0.12-win_x64")) {
    if ($cand -and (Test-Path "$cand\bin\javac.exe")) { $jdkHome = $cand; break }
}
if (-not $jdkHome) { Write-Output "no usable JDK found"; exit 1 }
Write-Output "JDK: $jdkHome"
$JAVAC = "$jdkHome\bin\javac.exe"
$JAVA = "$jdkHome\bin\java.exe"
$CFR = "$ROOT\bempdiff\cfr.jar"
$POI = "$ROOT\bempdiff\toolchain\lib\*"
$CORE_SRC = "$ROOT\bempdiff\java_core\src"
$TEST_SRC = "$ROOT\bempdiff\java_core\test"
$CORE_OUT = "$ROOT\bempdiff\java_core\out"
$TEST_OUT = "$ROOT\bempdiff\java_core\test_out"

# clean old class files so stale artifacts cannot mask compile errors
if (Test-Path $CORE_OUT) { Get-ChildItem $CORE_OUT -Recurse -File | Remove-Item -Force -ErrorAction SilentlyContinue }
if (Test-Path $TEST_OUT) { Get-ChildItem $TEST_OUT -Recurse -File | Remove-Item -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $CORE_OUT, $TEST_OUT | Out-Null

Write-Output "==> compile core src"
$coreFiles = (Get-ChildItem $CORE_SRC -Recurse -Filter *.java).FullName
& $JAVAC -cp "$CFR;$POI" -encoding UTF-8 -Xlint:-unchecked -d $CORE_OUT @coreFiles
if ($LASTEXITCODE -ne 0) { Write-Output "core compile FAILED ($LASTEXITCODE)"; exit 1 }
Write-Output "core compile OK"

Write-Output "==> compile test"
$testFiles = (Get-ChildItem $TEST_SRC -Recurse -Filter *.java).FullName
& $JAVAC -cp "$CORE_OUT;$CFR;$POI" -encoding UTF-8 -Xlint:-unchecked -d $TEST_OUT @testFiles
if ($LASTEXITCODE -ne 0) { Write-Output "test compile FAILED ($LASTEXITCODE)"; exit 1 }
Write-Output "test compile OK"

Write-Output "==> run TestRunner"
$tests = @(
    "com.bempdiff.test.ParseTest", "com.bempdiff.test.DiffTest", "com.bempdiff.test.DecompileTest",
    "com.bempdiff.test.AiTest", "com.bempdiff.test.ReportTest", "com.bempdiff.test.ExportTest",
    "com.bempdiff.test.ModelTest", "com.bempdiff.test.SsrfTest", "com.bempdiff.test.FrontendTest",
    "com.bempdiff.test.FolderTest", "com.bempdiff.test.FolderDiffTest", "com.bempdiff.test.ProfileTest",
    "com.bempdiff.test.VendorConfigTest", "com.bempdiff.test.ServerConfigTest", "com.bempdiff.test.ArchiveDiffTest",
    "com.bempdiff.test.ArchiveChildrenTest", "com.bempdiff.test.NestedZipDiffTest", "com.bempdiff.test.OfficeTextDiffTest",
    "com.bempdiff.test.DiffDigestTest", "com.bempdiff.test.PackageVersionTest", "com.bempdiff.test.FileOpsTest",
    "com.bempdiff.test.ProjectContextTest", "com.bempdiff.test.ProjectIndexerTest",
    "com.bempdiff.test.ProjectContextServiceTest", "com.bempdiff.test.ContextPromptTest",
    "com.bempdiff.test.ContextAiTest", "com.bempdiff.test.UnpackTest",
    "com.bempdiff.test.UpdateCheckServiceContractTest"
)
& $JAVA -cp "$TEST_OUT;$CORE_OUT;$CFR;$POI" "-Dfile.encoding=UTF-8" com.bempdiff.test.TestRunner @tests
Write-Output "testrunner exit: $LASTEXITCODE"
exit $LASTEXITCODE

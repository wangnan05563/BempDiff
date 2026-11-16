# T01549：JaCoCo 覆盖率采集链路（编译 → TestRunner 带 agent → jacoco.xml）
# 用法：powershell -ExecutionPolicy Bypass -File tooling/quality/jacoco/run_coverage.ps1
# 产物：release/quality/jacoco/jacoco.exec / jacoco.xml，供 sonar-scanner（sonar.coverage.jacoco.xmlReportPaths）读取。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$jre  = Join-Path $root 'bempdiff\toolchain\zulu21.52.15-ca-jdk21.0.12-win_x64\bin'
if (-not (Test-Path (Join-Path $jre 'java.exe'))) {
  $jre = Join-Path $root 'release\dist_input\jre\bin'   # 回退：打包快照内完整 JDK
}
$jc   = Join-Path $root 'tooling\quality\jacoco'
$agent = Join-Path $jc 'jacoco-agent-0.8.13.jar'
$cli   = Join-Path $jc 'jacoco-cli-0.8.13.jar'
$outDir = Join-Path $root 'release\quality\jacoco'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 1) 编译 core + test（Windows 原生脚本；走独立进程，避免 javac stderr 被 EAP=Stop 当成异常）
$build = Join-Path $PSScriptRoot '..\..\scripts\quality\build_and_test.ps1'
$buildProc = Start-Process -FilePath 'powershell' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $build) -Wait -PassThru -WindowStyle Hidden
if ($buildProc.ExitCode -ne 0) { throw "build_and_test 失败（exit $($buildProc.ExitCode)）" }
if (-not (Test-Path (Join-Path $root 'release\java_core\out\com'))) { throw 'core 未编译，请先跑 tooling/scripts/quality/build_and_test.ps1' }

# 2) TestRunner 全量挂 agent（类清单与 build_and_test.sh 保持一致）
$classes = @('ParseTest','DiffTest','DecompileTest','AiTest','ReportTest','ExportTest','ModelTest',
  'SsrfTest','FrontendTest','FolderTest','FolderDiffTest','ProfileTest','VendorConfigTest','ServerConfigTest',
  'ArchiveDiffTest','ArchiveChildrenTest','NestedZipDiffTest','OfficeTextDiffTest','DiffDigestTest',
  'PackageVersionTest','FileOpsTest','ProjectContextTest','ProjectIndexerTest','ProjectContextServiceTest',
  'ContextPromptTest','ContextAiTest','UnpackTest',
  'UpdateCheckServiceContractTest','ServerApiHttpTest','ServerCompareFlowTest','ServerStaticAndConfigTest',
  'MainCliTest','MainReflectTest','MarkdownParserTest','LibJarDiffTest','FolderReportTest','MarkdownReportBranchTest',
  'JobStateTest','CompareInputValidationTest','CompareOptionsRequestTest','UpdateCheckStubTest','NestedUnpackerBranchTest',
  'DecompilerBranchTest','OfficeTextDiffBranchTest','FrontendTextDiffBranchTest',
  'MemoryReleaseTest','MockAiAnalyzerTest') | ForEach-Object { "com.bempdiff.test.$_" }
$cp = @(
  (Join-Path $root 'release\java_core\test_out'),
  (Join-Path $root 'release\java_core\out'),
  (Join-Path $root 'bempdiff\cfr.jar'),
  (Join-Path $root 'bempdiff\toolchain\lib\*')
) -join ';'
& (Join-Path $jre 'java.exe') "-javaagent:$agent=destfile=$outDir\jacoco.exec,includes=com.bempdiff.*" `
  -cp $cp "-Dfile.encoding=UTF-8" com.bempdiff.test.TestRunner @classes
if ($LASTEXITCODE -ne 0) { throw "TestRunner 失败（exit $LASTEXITCODE）" }

# 3) 生成 Sonar 可读的 XML 报告
& (Join-Path $jre 'java.exe') -jar $cli report (Join-Path $outDir 'jacoco.exec') `
  --classfiles (Join-Path $root 'release\java_core\out\com') `
  --sourcefiles (Join-Path $root 'bempdiff\java_core\src') `
  --xml (Join-Path $outDir 'jacoco.xml')
if ($LASTEXITCODE -ne 0) { throw "jacoco report 失败（exit $LASTEXITCODE）" }
Write-Host "OK: $outDir\jacoco.xml"

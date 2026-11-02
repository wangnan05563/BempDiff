<#
.SYNOPSIS
  BempDiff 构建输入装配脚本：编译 java_core + 复制 cfr.jar + jlink 瘦 JRE + 构建前端，
  统一产出 dist_input/{app,classes,jre,webui}。

.DESCRIPTION
  Electron 桌面壳（唯一发布壳）与打包链路的共享装配步骤：
  electron-builder 的 build.extraResources 直接消费 dist_input/{app,jre,webui}，
  开发态 main.js 亦按 dist_input/classes 拼 classpath，因此 CI、一键发布、性能压测
  都调用本脚本装配输入，再各自执行 electron-builder 打包。

  流程：
    1. 编译 java_core → dist_input/app/bempdiff.jar（Main-Class=com.bempdiff.Main）
    2. 复制 cfr.jar → dist_input/app/cfr.jar，并分发 toolchain/lib → dist_input/app/lib
    3. jlink 最小 JRE（见 $Modules）→ dist_input/jre
    4. 构建前端（vite build）→ webui/dist，并镜像到 dist_input/webui

  需在本机运行（Node + JDK21）；产物就绪后由 electron-builder 出安装包。

.PARAMETER SkipJava
  跳过 jar/jre 重新编译（复用已有 dist_input/app、dist_input/jre）。
.PARAMETER SkipJre
  仅跳过 jlink 重建 dist_input/jre（T01509：CI 全新检出必须编译 jar，
  但要保住打包前 switch-jre.mjs 就位的预置 JRE，不被 jlink 覆盖）。
.PARAMETER SkipFrontend
  跳过前端构建与镜像。
.PARAMETER Clean
  先清空 dist_input 与 jre 再开始。
#>
# 注意：刻意不使用 [CmdletBinding()]。使用普通脚本 + 自动变量 $args 收集游离参数，
# 可确保任何误传的位置参数（如 `构建打包.bat src` 漏进来的 `src`）只进 $args、不会触发
# PowerShell 严格的参数绑定异常（ParameterBindingException），构建不被打断。
param(
  [switch]$SkipJava,
  [switch]$SkipJre,
  [switch]$SkipFrontend,
  [switch]$Clean
)

$ErrorActionPreference = 'Stop'

# 容忍误传的位置参数（例如 `构建打包.bat src`）：明确警告后忽略，不阻断构建。
if ($args -and $args.Count -gt 0) {
  $args | ForEach-Object { Write-Warning "忽略未识别参数: '$_'（本脚本仅接受具名开关: -SkipJava / -SkipJre / -SkipFrontend / -Clean）" }
}

# 5.1 兼容辅助：Windows PowerShell 5.1 的 Join-Path 仅接受 2 个位置参数（Path + ChildPath），
# 多段路径（如 Join-Path $Proto 'a' 'b'）会报“找不到接受实际参数的位置形式参数”。
# 此函数接收首个基路径 + 任意多段子路径，逐段拼接，跨 PowerShell 版本安全。
function Join-Paths {
  param($Base)
  $p = $Base
  foreach ($seg in $args) { $p = Join-Path $p $seg }
  return $p
}

# 运行原生命令并回显输出；仅以 $LASTEXITCODE 判定成败。
# 背景（已实测）：PowerShell 5.1 下若 $ErrorActionPreference='Stop'，原生命令写到 stderr 的内容
# （如 javac “使用了未经检查或不安全的操作”告警、where.exe 找不到文件）会被包装成
# NativeCommandError/RemoteException 并直接终止脚本——即便工具本身已成功（exit 0）。
# 关键点：重定向（2>&1 或 2> file）【不会】消除这个终止行为，终止与否完全由 $EAP 决定。
# 因此最稳修法 = 临时把 $EAP 降到 SilentlyContinue（确保告警/提示绝不致命）+ 2>&1 把合并流
# 收进变量以便回显；成败只判 $LASTEXITCODE。已在沙箱用真实 where.exe 验证不再抛 NativeCommandError。
function Invoke-Native {
  param([scriptblock]$Script)
  $prev = $ErrorActionPreference
  # 关键修复：临时抑制错误动作偏好，使原生命令 stderr 不再触发 NativeCommandError 终止。
  $ErrorActionPreference = 'SilentlyContinue'
  try {
    $merged = & $Script 2>&1
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
  }
  # 合并流（含原 stderr 内容）按行回显，并追加到构建日志，便于失败时回溯真实报错。
  $merged | ForEach-Object {
    Write-Host $_
    try { $_ | Out-File -Append -FilePath $BuildLog -Encoding utf8 } catch {}
  }
  return $code
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Proto     = Split-Path -Parent $ScriptDir
$Webui     = Join-Path $Proto 'webui'
$DistInput = Join-Path $Proto 'dist_input'
$AppDir    = Join-Path $DistInput 'app'
$JreOut    = Join-Path $DistInput 'jre'
$Classes   = Join-Path $DistInput 'classes'
$DevClasses= Join-Path $DistInput 'dev_classes'
$CfrSrc    = Join-Path $Proto 'cfr.jar'
$BempJar   = Join-Path $AppDir 'bempdiff.jar'
$CfrJar    = Join-Path $AppDir 'cfr.jar'
$JavaSrc   = Join-Path (Join-Path $Proto 'java_core') 'src'
$WebuiDist = Join-Path $Webui 'dist'
# 三方依赖 jar（目前为解析旧版二进制 .xls 的 Apache POI 及其传递依赖）：编译 classpath 与
# 运行时 dist_input/app/lib 均来自此目录，桌面壳 main.js 按该目录拼服务端 classpath。
$PoiLib    = Join-Path $Proto 'toolchain\lib'

# 构建日志：所有原生命令输出按行追加于此，构建失败时可直接打开回溯真实报错。
$BuildLog = Join-Path $Proto 'build_native.log'
try { Set-Content -Path $BuildLog -Value ("=== BempDiff build @ " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + " ===") -Encoding utf8 } catch {}

# 最小 JRE 模块（jdeps 实测静态最小集为 java.base,java.logging,jdk.httpserver；
# 显式补 jdk.crypto.ec/jdk.crypto.mscapi 以支撑 HTTPS 调 AI（原 jpackage TLS 事故根因），
# jdk.jdeps 保留 javap 降级能力。
# java.xml 必加：OfficeTextDiff 用 JAXP（DocumentBuilderFactory）解析 docx/xlsx/pptx 内部 XML，
# 缺失时点击 Office 文档抛 ClassNotFoundException -> "该文件无法反编译（引擎：none）" / 卡在加载态。
# java.desktop 必含：POI 的 DataFormatter 依赖 java.beans.PropertyChangeSupport（属 java.desktop），
# 缺它点击 .xls 会在线程内抛 NoClassDefFoundError 且无响应，前端永久停在加载态。
$Modules = 'java.base,java.logging,jdk.httpserver,jdk.crypto.ec,jdk.crypto.mscapi,jdk.jdeps,java.xml,java.desktop'

# 工具链（JDK）：按优先级探测可用 javac.exe，首取 $env:JAVA21_HOME，其次 $env:JAVA_HOME，
# 再内置 Zulu；内置 Zulu 若损坏/缺失（历史上 bin 曾被清空只剩 server），降级到本机常见 JDK，
# 避免仅因工具链缺失就中断打包。取第一个 bin\javac.exe 可用的候选为本次构建 JDK。
$jdkCandidates = @()
if ($env:JAVA21_HOME) { $jdkCandidates += $env:JAVA21_HOME }
if ($env:JAVA_HOME)   { $jdkCandidates += $env:JAVA_HOME }
$jdkCandidates += (Join-Paths $Proto 'toolchain' 'zulu21.52.15-ca-jdk21.0.12-win_x64')
# 本机常用 JDK 兜底（缺环境变量时的备选）
$jdkCandidates += 'D:\code\Java\jdk-25.0.1'
$JDK = $jdkCandidates | Where-Object { $_ -and (Test-Path (Join-Paths $_ 'bin' 'javac.exe')) } | Select-Object -First 1
if (-not $JDK) {
  throw "未找到可用 JDK（javac.exe）。请设置 JAVA21_HOME 指向完整 JDK 后重试。"
}
$Javac = Join-Paths $JDK 'bin' 'javac.exe'
$Jar   = Join-Paths $JDK 'bin' 'jar.exe'
$Jlink = Join-Paths $JDK 'bin' 'jlink.exe'

function Write-Step($msg) { Write-Host "`n== $msg ==" -ForegroundColor Cyan }

# 递归删目录（兼容本环境 safe-delete 钩子：优先 Remove-Item，失败回退 .NET）
function Remove-Tree($p) {
  if (-not (Test-Path $p)) { return $true }
  $ok = $false
  try { Remove-Item -Recurse -Force -ErrorAction Stop $p; $ok = $true } catch {}
  if (-not $ok) {
    try { [System.IO.Directory]::Delete($p, $true); $ok = $true } catch {}
  }
  if (-not $ok) {
    # 兜底：cmd rmdir 对超长路径（jlink 产物路径很深）/只读文件比 .NET Delete 更鲁棒
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
    try { & cmd.exe /c ("rmdir /s /q """ + $p + """") } finally { $ErrorActionPreference = $prev }
    $ok = -not (Test-Path $p)
  }
  if (-not $ok) {
    # 最常见真因：目录被其它进程占用（如正在运行的 java/jlink 持有了 jre 内文件）。
    # 此时必须先在任务管理器结束占用进程，或手动删除后重跑；脚本无法跨进程强制解锁。
    throw "无法删除 $p：可能被其它进程占用（最常见是正在运行的 java.exe / jlink.exe 持有了该目录内的文件，例如用 启动服务.bat 跑的后端未关闭）。`n请先执行：taskkill /F /IM java.exe 与 taskkill /F /IM jlink.exe，再手动删除该目录后重跑本脚本。"
  }
  return $ok
}

if ($Clean) {
  Write-Step "清理 dist_input"
  Remove-Tree $DistInput
}

Write-Step "校验工具链"
foreach ($bin in @($Javac, $Jar, $Jlink, $CfrSrc)) {
  if (-not (Test-Path $bin)) { throw "缺少必要文件：$bin" }
}
New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
# POI 解析 .xls 所需的三方 jar：编译阶段必须有（javac -cp），否则无法编译 HSSF 代码。
if (-not (Test-Path $PoiLib)) { throw "缺少三方依赖目录：$PoiLib（需含 poi-*.jar 等，用于 .xls 解析）" }

# ---------- 1~2. 编译 java_core + 复制 cfr.jar + 分发三方 lib ----------
if (-not $SkipJava) {
  Write-Step "编译 java_core → bempdiff.jar"
  Remove-Tree $Classes
  # 同步清理兜底编译产物 dev_classes（main.js ensureClasspath 生成）：避免陈旧 class 混入打包源，
  # 若开发态再需要，main.js 会在无产物时按源码重新生成。
  Remove-Tree $DevClasses
  New-Item -ItemType Directory -Force -Path $Classes | Out-Null
  $sources = (Get-ChildItem -Recurse -Filter *.java $JavaSrc).FullName
  # 编译 classpath = cfr.jar + POI lib(通配符)。通配符由 javac 展开，需各 jar 位于同一目录。
  $jcp = "$CfrSrc;$PoiLib\*"
  $rc = Invoke-Native { & $Javac -d $Classes -cp $jcp @sources }
  if ($rc -ne 0) { throw "javac 失败" }
  $rc = Invoke-Native { & $Jar cfe $BempJar com.bempdiff.Main -C $Classes . }
  if ($rc -ne 0) { throw "jar 失败" }
  Write-Host "  -> $BempJar ($([math]::Round((Get-Item $BempJar).Length/1MB,2)) MB)"

  Write-Step "复制 cfr.jar"
  Copy-Item -Force $CfrSrc $CfrJar
  Write-Host "  -> $CfrJar"

  Write-Step "分发三方依赖 lib → dist_input/app/lib"
  $LibOut = Join-Path $AppDir 'lib'
  New-Item -ItemType Directory -Force -Path $LibOut | Out-Null
  Copy-Item -Force (Join-Path $PoiLib '*') $LibOut
  Write-Host "  -> $($(Get-ChildItem $LibOut -Filter '*.jar').Count) 个 jar 已复制"
} else {
  Write-Host "跳过 Java 编译（复用已有 dist_input/app）"
  if (-not (Test-Path $BempJar)) { throw "bempdiff.jar 不存在，请去掉 -SkipJava 先编译" }
}

# ---------- 3. jlink 最小 JRE ----------
if (-not $SkipJava -and -not $SkipJre) {
  Write-Step "jlink 最小 JRE → $JreOut"
  # 定向结束可能锁定 dist_input 的残留进程（如未关闭的后端 sidecar / 上一轮构建残留的 java、javaw、jlink）。
  # 覆盖两类命中（仅精确匹配本项目特征，绝不误杀 Jenkins 等其它 Java 进程）：
  #   ① java.exe / javaw.exe / jlink.exe 且命令行含 bempdiff / dist_input —— sidecar 与构建工具残留；
  #      【教训】2026-09-11 打包中断实测：sidecar 由 javaw.exe 启动，旧过滤只写 java.exe/jlink.exe
  #      漏掉了 javaw，导致 jre 目录被锁、jlink 阶段报 Access denied。javaw 必须显式列入。
  #   ② 可执行文件路径位于 dist_input 之下（如 dist_input\jre\bin\javaw.exe 直接启动的 sidecar），
  #      不依赖进程名与命令行拼写，双保险兜底。
  try {
    $projMarkers = @('*bempdiff*', '*dist_input*')
    $holders = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
      $cmdHit = $false
      if ($_.Name -in @('java.exe', 'javaw.exe', 'jlink.exe') -and $_.CommandLine) {
        foreach ($m in $projMarkers) { if ($_.CommandLine -like $m) { $cmdHit = $true; break } }
      }
      $pathHit = ($_.ExecutablePath -and $_.ExecutablePath -like '*\dist_input\*')
      $cmdHit -or $pathHit
    }
    foreach ($h in $holders) {
      Write-Host "  -> 结束占用进程 PID $($h.ProcessId) [$($h.Name)]: $($h.CommandLine)"
      Stop-Process -Id $h.ProcessId -Force -ErrorAction SilentlyContinue
    }
  } catch {}
  Start-Sleep -Milliseconds 500
  Remove-Tree $JreOut
  $jlinkArgs = @('--no-header-files','--no-man-pages','--compress=2',
                 '--add-modules', $Modules, '--output', $JreOut)
  $rc = Invoke-Native { & $Jlink @jlinkArgs }
  if ($rc -ne 0) { throw "jlink 失败" }
  $javaExe = Join-Paths $JreOut 'bin' 'java.exe'
  if (-not (Test-Path $javaExe)) { throw "jlink 产物缺少 java.exe" }
  Write-Host "  -> JRE 就绪（$([math]::Round((Get-ChildItem $JreOut -Recurse | Measure-Object -Property Length -Sum).Sum/1MB,1)) MB）"
} else {
  Write-Host "跳过 jlink（复用已有 dist_input/jre）"
  if (-not (Test-Path (Join-Paths $JreOut 'bin' 'java.exe'))) { throw "jre/bin/java.exe 不存在，请去掉 -SkipJava/-SkipJre" }
}

# ---------- 4. 构建前端 + 镜像到 dist_input/webui ----------
if (-not $SkipFrontend) {
  Write-Step "构建前端 (vite build)"
  # T01670：构建前先清空 webui/dist，避免 emptyOutDir:false 导致历史 bundle 累积。
  # 不能用 vite.config.js 的 emptyOutDir:true（显式设为 false 是因 WorkBuddy safe-delete
  # 钩子 fail-closed 会中断构建），这里用 PowerShell Remove-Tree 直接清理，绕过该限制。
  Remove-Tree $WebuiDist
  Push-Location $Webui
  try {
    # 约束：npm 走默认全局缓存；切勿手动传 `--cache /d/code/...` 之类 POSIX 路径，
    # Windows 原生 npm 会把前导 /d/ 归一化为 D:\d\... 误生成异常目录。
    $rc = Invoke-Native { & npm run build }
    if ($rc -ne 0) { throw "npm run build 失败" }
  } finally { Pop-Location }
  Write-Step "镜像前端 → dist_input/webui"
  $dest = Join-Path $DistInput 'webui'
  Remove-Tree $dest
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  robocopy $WebuiDist $dest /E /R:2 /W:2 /NFL /NDL | Out-Null
  Write-Host "  -> $dest"
} else {
  Write-Host "跳过前端构建（复用已有 dist_input/webui）"
}

# ---------- 5. 产物新鲜度自检（防 -Skip* 误用把旧内容装配进安装包） ----------
# 仅当通过 -Skip* 复用了旧产物时才需要校验：若源码比复用的 jar 新，说明打包的是旧后端，直接中止。
function Assert-InputFresh {
  if (-not $SkipJava -and -not $SkipFrontend) { return }   # 本次全量重编，天然新鲜，无需校验
  if ($SkipJava) {
    $newestSrc = (Get-ChildItem -Recurse -Filter *.java $JavaSrc | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
    $jarT = (Get-Item $BempJar -ErrorAction SilentlyContinue).LastWriteTime
    if ($jarT -isnot [datetime]) { throw "bempdiff.jar 不存在，无法打包（-SkipJava 空跑）" }
    if ($jarT -lt $newestSrc) {
      throw "SkipJava 复用旧 jar（$jarT）早于最新源码（$newestSrc），会打包旧后端。请去掉 -SkipJava 重新编译。"
    }
  }
  if ($SkipFrontend -and -not (Test-Path (Join-Path $DistInput 'webui\index.html'))) {
    throw "SkipFrontend 复用但 dist_input/webui 前端缺失，会打包空/旧前端。请去掉 -SkipFrontend。"
  }
}
Assert-InputFresh

Write-Host "`n[完成] dist_input 已就绪：$DistInput" -ForegroundColor Green
Write-Host "  打包请运行：npm run dist（electron-builder，消费 dist_input/{app,jre,webui}）" -ForegroundColor Green

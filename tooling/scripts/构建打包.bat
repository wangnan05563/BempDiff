@echo off
REM Switch console to UTF-8: the PS1 helper scripts emit Chinese via stdout; without this,
REM cmd would decode those UTF-8 bytes as GBK and the info lines turn into mojibake.
REM NOTE: this batch itself MUST be pure ASCII (CRLF) - avoid non-ASCII chars here entirely.
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0\..\..\bempdiff"

REM ---------- Binary mirrors (avoid corporate artifactory block for NSIS/electron) ----------
set "ELECTRON_BUILDER_BINARIES_MIRROR=https://registry.npmmirror.com/-/binary/electron-builder-binaries/"
set "ELECTRON_MIRROR=https://registry.npmmirror.com/-/binary/electron/"

echo ============================================
echo   BempDiff Build (Electron + Web UI + Java sidecar)
echo ============================================
echo Steps: check java artifacts -^> install builder -^> build webui -^> electron-builder [NSIS]
echo Output: release\BempDiff-*-setup.exe  (project root 18_comparePakage\release)
echo.

REM ---------- 0. Version bump: unique patch = (yyyyMMdd).(daily seq), written to root package.json.
REM          Runs before any build step so every artifact shares one version.
echo [STEP] Bumping patch version (yyyyMMdd + daily seq) ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\..\bempdiff\scripts\bump_patch.ps1" -Root "%~dp0..\..\bempdiff"
if errorlevel 1 (
  echo "[ERROR] patch version bump failed"
  pause
  exit /b 1
)
echo [OK] Patch version bumped.

REM ---------- 0. Prerequisite: Java backend artifacts ----------
if not exist "dist_input\jre\bin\javaw.exe" (
  echo "[ERROR] dist_input\jre not found - build the Java backend (javac/jar/cfr/jlink) beforehand"
  pause
  exit /b 1
)
if not exist "dist_input\app\cfr.jar" (
  echo "[ERROR] dist_input\app\cfr.jar not found - build the Java backend beforehand"
  pause
  exit /b 1
)
set "_has_backend=0"
if exist "dist_input\classes" set "_has_backend=1"
if exist "dist_input\app\bempdiff.jar" set "_has_backend=1"
if "%_has_backend%"=="0" (
  echo "[ERROR] No backend artifact (dist_input\classes or dist_input\app\bempdiff.jar) - build the Java backend beforehand"
  pause
  exit /b 1
)
echo [OK] Java backend artifacts present.

REM ---------- 1. Install electron-builder (here, in bempdiff/) ----------
echo [STEP] Installing electron-builder ...
REM Use PowerShell to call npm.cmd instead of bare "call npm": under cmd, npm 11 can intermittently
REM mis-parse --no-fund into "'o-fund' is not recognized" (a fund cleanup child spawned by install gets
REM its args re-split via cmd /c). The same command run under PowerShell never reproduces this.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Push-Location '%CD%'; npm.cmd install --no-audit --no-fund; $code=$LASTEXITCODE; Pop-Location; exit $code"
if errorlevel 1 (
  echo "[ERROR] npm install failed"
  pause
  exit /b 1
)

REM ---------- 2. Ensure dev-shell Electron runtime is installed ----------
if not exist "dev-shell\node_modules\.bin\electron.cmd" (
  echo [STEP] Installing Electron runtime into dev-shell ...
  pushd dev-shell
  REM Same as step 1: use PowerShell to avoid the intermittent cmd "'o-fund' is not recognized"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "npm.cmd install --no-audit --no-fund; exit $LASTEXITCODE"
  popd
  if errorlevel 1 (
    echo "[ERROR] electron install failed"
    pause
    exit /b 1
  )
) else (
  echo "[OK] dev-shell Electron runtime present"
)

REM ---------- 3. Build webui (produces webui/dist served by backend --webroot) ----------
echo [STEP] Building webui (vite build) ...
pushd webui
REM Same as step 1: use PowerShell to avoid the intermittent cmd "'o-fund' is not recognized"
powershell -NoProfile -ExecutionPolicy Bypass -Command "npm.cmd install --no-audit --no-fund; exit $LASTEXITCODE"
if errorlevel 1 (
  echo "[ERROR] webui npm install failed"
  pause
  exit /b 1
)
call npm run build
if errorlevel 1 (
  echo "[ERROR] webui build failed"
  pause
  exit /b 1
)
popd
echo [OK] webui/dist ready.

REM ---------- 4. Unified assemble: force rebuild Java jar/classes + refresh dist_input/webui ----------
REM   Reuses build_dist_input.ps1 assemble logic so BOTH packagers share ONE refreshed dist_input.
echo [STEP] Assembling dist_input (recompile jar, refresh webui) ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\build_dist_input.ps1"
if errorlevel 1 (
  echo [ERROR] assemble failed - check bempdiff\build_native.log
  pause
  exit /b 1
)
echo [OK] dist_input refreshed.

REM ---------- 4.5 Pre-clean win-unpacked: electron-builder's first step (emptyDir on the app
REM   out dir) must delete release\win-unpacked\resources\app.asar; a leftover process holding the
REM   file (AV scanner, desktop search index) makes it die with a cryptic stack that hides the
REM   real holder. NOTE: '$pid' is a READ-ONLY automatic var in PowerShell - the helper used to
REM   assign to it and aborted before printing the holder (fixed 2026-09-16).
REM   If the lock persists we do NOT fail the build: we switch electron-builder to a brand new
REM   output directory (release\_bk-<timestamp>), which needs no deletion at all.
echo [STEP] Pre-cleaning release\win-unpacked ...
set "_outrel=..\release"
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\pre_clean_release.ps1"
if errorlevel 1 (
  echo [WARN] win-unpacked is locked by another process - see diagnostics above.
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Write-Output (Get-Date -Format 'yyyyMMdd-HHmmss')" > "..\release\.altstamp"
  set /p "_stamp=" < "..\release\.altstamp"
)
if defined _stamp (
  set "_bkname=_bk-%_stamp%-%RANDOM%"
)
if defined _bkname (
  set "_outrel=..\release\%_bkname%"
  set "_outcfg=../release/%_bkname%"
  echo [INFO] Falling back to fresh output directory: ..\release\%_bkname%\win-unpacked
  echo [INFO] Stale release\win-unpacked stays on disk - delete it once the holder is gone.
)

REM ---------- 5. Package with electron-builder (NSIS) ----------
echo [STEP] Running electron-builder (NSIS) ...
REM Persist the NSIS disk-space-precheck patch in-repo: overwrite the node_modules template before
REM packaging (idempotent, reproducible). Fixes false "insufficient disk space" from GetDiskFreeSpaceEx
REM under NTFS quota / compressed folder / AV filter driver / virtualization redirect environments.
echo [STEP] Applying NSIS disk-space precheck patch ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\patch_nsis_spacecheck.ps1"
if errorlevel 1 (
  echo "[ERROR] NSIS disk-space precheck patch failed"
  pause
  exit /b 1
)
REM electron-builder / NSIS self-extract and 7z compression write temp files into %TMP% (usually C:).
REM If that drive is full, only a ~200KB stub setup.exe remains yet electron-builder reports success.
REM Redirect TMP/TEMP to the release drive (D) during packaging, then restore them afterwards.
REM In rare shells %TMP%/%TEMP% may be empty; on restore fall back to the system default so we
REM never leave TMP=TEMP cleared for the rest of the script (e.g. the dist_verify step below).
if not exist "..\release\.buildtmp" mkdir "..\release\.buildtmp"
set "_orig_tmp=%TMP%"
if "%_orig_tmp%"=="" set "_orig_tmp=%SystemRoot%\Temp"
set "_orig_temp=%TEMP%"
if "%_orig_temp%"=="" set "_orig_temp=%_orig_tmp%"
set "TMP=%CD%\..\release\.buildtmp"
set "TEMP=%CD%\..\release\.buildtmp"

REM Read the version BEFORE packaging. Two reasons:
REM   (a) Delete any previous installer of the SAME version first. A locked leftover (antivirus
REM       scanner, Explorer preview) makes electron-builder log "output file is locked for
REM       writing => waiting for unlock" and then skip producing a new file while still exiting 0.
REM   (b) The freshness check below must never accept an OLD release as this build.
REM NOTE: read it with node, NOT PowerShell ConvertFrom-Json. PowerShell 5.1 decodes a BOM-less
REM UTF-8 file with the ANSI codepage, which mangles the Chinese "description" field and makes
REM ConvertFrom-Json fail ("invalid object, expected ':' or '}'"), yielding an EMPTY version --
REM that is why the check used to look for "BempDiff--setup.exe" and always reported "not produced".
node -p "require('./dev-shell/package.json').version" > "..\release\.version" 2>nul
set /p "_ver=" < "..\release\.version"
if not defined _ver (
  echo "[ERROR] Unable to read version from dev-shell\package.json"
  pause
  exit /b 1
)
echo [INFO] Target version: %_ver%
if exist "%_outrel%\BempDiff-%_ver%-setup.exe" (
  echo [STEP] Removing previous installer for %_ver% ...
  del /q "%_outrel%\BempDiff-%_ver%-setup.exe" >nul 2>&1
  if exist "%_outrel%\BempDiff-%_ver%-setup.exe" (
    echo [WARNING] Previous installer is still locked - electron-builder may fail to overwrite it.
    echo [WARNING] Close whatever holds BempDiff-%_ver%-setup.exe, then re-run.
  )
)
if exist "%_outrel%\BempDiff-%_ver%-setup.exe.blockmap" del /q "%_outrel%\BempDiff-%_ver%-setup.exe.blockmap" >nul 2>&1

if defined _outcfg (
  echo [STEP] Packaging into %_outrel% ...
  call npm run dist -- --config.directories.output=%_outcfg%
) else (
  call npm run dist
)
set "_dist_err=%errorlevel%"
set "TMP=%_orig_tmp%"
set "TEMP=%_orig_temp%"
if "%_dist_err%"=="0" goto dist_check_size
REM electron-builder exit != 0 may be sandbox safe-delete failing to clean temp files.
REM We do NOT blindly trust "installer exists": a disk-full can leave a truncated stub .exe.
echo "[ERROR] electron-builder exited %_dist_err% (see output above)"
pause
exit /b 1
:dist_check_size
REM Guard against a truncated/stub installer: a disk-full can leave a ~200KB stub .exe
REM that still "exists" yet is useless. The REAL version source is dev-shell/package.json
REM (electron-builder reads it because build.directories.app = dev-shell). _ver was already
REM read BEFORE packaging and any previous installer of that exact version was deleted there,
REM so "exists and >= 50MB" here can only mean THIS build produced it - no time window needed
REM (a large app on a slow disk can take longer than any fixed freshness threshold).
if not defined _ver (
  echo "[ERROR] Unable to read version from dev-shell\package.json"
  pause
  exit /b 1
)
REM Require the exact fresh installer (>= 50MB) named after $_ver.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f = Join-Path '%CD%\%_outrel%' ('BempDiff-{0}-setup.exe' -f $env:_ver); if (-not (Test-Path $f)) { Write-Error ('MISSING: ' + $f + ' (ver=[' + $env:_ver + '])'); exit 1 }; $len = (Get-Item $f).Length; if ($len -ge 52428800) { Write-Output $f } else { Write-Output ('STUB:' + $f) }" > "%_outrel%\.last_valid_exe"
set /p "_valid_exe=" < "%_outrel%\.last_valid_exe"
if not defined _valid_exe (
  echo "[ERROR] No installer produced for version %_ver% - disk space issue or build failure. Check C:\ space."
  pause
  exit /b 1
)
if "%_valid_exe:~0,5%"=="STUB:" (
  echo [ERROR] Installer for version %_ver% is a truncated stub ^(smaller than 50MB^) - disk full. Check C:\ space.
  pause
  exit /b 1
)
echo [OK] Valid installer produced: %_valid_exe%
del /q "%_outrel%\.last_valid_exe" >nul 2>&1

REM ---- Promote the fresh installer to the release ROOT ----
REM When win-unpacked is locked by a scanner/indexer we fall back to a _bk-<ts> output dir,
REM so the fresh setup.exe would otherwise live ONLY under _bk-<ts> and the usual "\release\"
REM pickup would hand out a STALE build (users report "fix not applied"). Copy the fresh
REM installers (exe + blockmap) back to the root and re-check the root copy with the same
REM >=50MB stub guard, so this script always leaves a valid latest installer in release\.
if defined _bkname (
  echo [STEP] Promoting fresh installer from %_outrel% to release root ...
  copy /y "%_outrel%\BempDiff-%_ver%-setup.exe" "..\release\BempDiff-%_ver%-setup.exe" >nul
  if exist "%_outrel%\BempDiff-%_ver%-setup.exe.blockmap" (
    copy /y "%_outrel%\BempDiff-%_ver%-setup.exe.blockmap" "..\release\BempDiff-%_ver%-setup.exe.blockmap" >nul
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$f = '%CD%\..\release\BempDiff-%_ver%-setup.exe'; if (-not (Test-Path $f)) { Write-Error ('MISSING promoted: ' + $f); exit 1 }; $l = (Get-Item $f).Length; if ($l -lt 52428800) { Write-Error ('STUB promoted: ' + $f); exit 1 }"
  if errorlevel 1 (
    echo [ERROR] Failed to promote fresh installer to release root
    pause
    exit /b 1
  )
  echo [OK] Fresh installer promoted to release root.
)
REM Canonical deliverable: absolute-ish path from the bempdiff CWD to the release-root installer.
set "_ship_exe=..\release\BempDiff-%_ver%-setup.exe"
goto dist_verify

REM ---------- 6. Sanity: packaged jar hash must match the freshly assembled jar ----------
:dist_verify
echo [STEP] Verifying packaged jar hash == fresh dist_input jar ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%CD%\%_outrel%\win-unpacked\resources\bempdiff\dist_input\app\bempdiff.jar'; $f='%CD%\dist_input\app\bempdiff.jar'; $hp=Get-FileHash $p -ErrorAction SilentlyContinue; $hf=Get-FileHash $f -ErrorAction SilentlyContinue; if($hp -and $hf -and $hp.Hash -eq $hf.Hash){ echo [OK] packaged jar matches fresh build } else { echo [ERROR] packaged jar is STALE/differs - do NOT distribute this installer; exit 1 }"
if errorlevel 1 (
  echo [ERROR] Stale jar detected in installer - build aborted
  pause
  exit /b 1
)

REM ---------- 7. Post-pack verify (T01507): installer location + webui bundle freshness ----------
REM   1) Locate the canonical installer for %_ver% across release root AND any _bk-* dirs
REM      (>=50MB rule); warns loudly on the stale-root scenario so nobody picks an old build.
REM   2) Compare packaged win-unpacked webui index.html bundle hashes vs source dist_input webui
REM      (guards "packaged OK but ships an old frontend").
echo [STEP] Post-pack verify: installer location + webui bundle freshness ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0post_pack_verify.ps1" -ReleaseDir "%CD%\..\release" -Version "%_ver%" -SourceWebuiIndex "%CD%\dist_input\webui\index.html"
if errorlevel 1 (
  echo [ERROR] post-pack verify failed - see [ERROR] lines above
  pause
  exit /b 1
)

echo.
echo [DONE] Build complete.
REM Always print the canonical release-ROOT deliverable (absolute path + version), so
REM whoever picks from "\release\" gets an unambiguous, correct target regardless of whether
REM this run used the default root output or fell back to a _bk-<ts> dir (which is only a
REM temp staging location and is NEVER the sole home of the latest build).
set "_abs_ship=%CD%\..\release\BempDiff-%_ver%-setup.exe"
echo   Version:    %_ver%
echo   Installer:  %_abs_ship%
echo.
echo Finished. Press any key to close this window...
pause >nul
exit /b 0

@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
REM NOTE: this batch itself MUST be pure ASCII (CRLF) - no non-ASCII chars anywhere.
REM   Reason: cmd.exe buffers the first ~1KB of a .bat using the OEM code page (GBK on
REM   zh-CN Windows) BEFORE `chcp 65001` takes effect. UTF-8 Chinese REM/echo lines in
REM   the header get decoded as GBK and their fragments run as commands -> garbling.
REM   Keep ALL text here in ASCII. Chinese output (if any) must come from PS/Python helpers.
REM ============================================================
REM BempDiff: one-shot build + publish to GitHub Release
REM ------------------------------------------------------------
REM Steps:
REM   1) install deps (bempdiff / webui / dev-shell)
REM   2) assemble dist_input (compile jar + jlink JRE + vite build + mirror)
REM   3) electron-builder NSIS installer (x64, output to repo-root release/)
REM   4) read notes md (release-notes-<ver>.md, fallback to git log), then call
REM      publish-release.py to create the Release + upload the installer asset
REM
REM Usage: double-click from tooling/scripts, or run from repo root: tooling\scripts\<this file>
REM
REM Dry run (build + print publish actions, NO actual GitHub publish):
REM         set GH_DRY_RUN=1 && tooling\scripts\<this file>
REM
REM Prereq: Node + npm, JDK21 (build_dist_input.ps1 auto-detects zulu/local JDK),
REM         git (infers repo/tag/branch, reads github.com token from git credential store)
REM ============================================================

REM ---------- Paths ----------
set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%..\.."
for %%I in ("%REPO_ROOT%") do set "REPO_ROOT=%%~fI"
set "BEMPDIFF=%REPO_ROOT%\bempdiff"
set "RELEASE_DIR=%REPO_ROOT%\release"
set "PUBLISH_PY=%REPO_ROOT%\tooling\scripts\publish-release.py"
set "GEN_NOTES=%REPO_ROOT%\tooling\scripts\gen-release-notes.py"

REM ---------- Python exe (prefer python, else python3) ----------
set "PYEXE=python"
where python >nul 2>nul || set "PYEXE=python3"

REM ---------- Electron mirrors (faster in CN; safe to delete these 2 lines elsewhere) ----------
set "ELECTRON_MIRROR=https://registry.npmmirror.com/-/binary/electron/"
set "ELECTRON_BUILDER_BINARIES_MIRROR=https://registry.npmmirror.com/-/binary/electron-builder-binaries/"

cd /d "%REPO_ROOT%"

REM ============ 1) Install dependencies ============
echo [1/4] Installing dependencies (bempdiff / webui / dev-shell) ...
cd /d "%BEMPDIFF%"
call npm install --no-audit --no-fund
if errorlevel 1 ( echo [FAIL] bempdiff npm install failed & exit /b 1 )
call npm --prefix webui install --no-audit --no-fund
if errorlevel 1 ( echo [FAIL] webui npm install failed & exit /b 1 )
call npm --prefix dev-shell install --no-audit --no-fund
if errorlevel 1 ( echo [FAIL] dev-shell npm install failed & exit /b 1 )

REM ============ 2) Assemble dist_input (jar + jlink JRE + frontend) ============
echo [2/4] Assembling dist_input (compile jar + jlink JRE + build frontend) ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%BEMPDIFF%\scripts\build_dist_input.ps1"
if errorlevel 1 ( echo [FAIL] assemble dist_input failed, abort & exit /b 1 )

REM ============ 3) electron-builder NSIS installer (x64) ============
echo [3/4] electron-builder NSIS installer (x64) ...
REM Read target version from dev-shell/package.json before packaging.
cd /d "%BEMPDIFF%"
if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
if not defined VER (
  node -p "require('./dev-shell/package.json').version" > "%RELEASE_DIR%\.version" 2>nul
  set /p "VER=" < "%RELEASE_DIR%\.version"
  del /q "%RELEASE_DIR%\.version" >nul 2>&1
)
if not defined VER ( echo [FAIL] cannot read version from dev-shell/package.json & exit /b 1 )
echo   Target version: %VER%

REM Delete stale setup / blockmap before packaging to avoid stale or output-locked files in _bk dirs
if exist "%RELEASE_DIR%" (
  for %%f in ("%RELEASE_DIR%\BempDiff-*-setup.exe" "%RELEASE_DIR%\BempDiff-*-setup.exe.blockmap") do del /q "%%f" >nul 2>&1
)

REM If win-unpacked is locked, package into a fresh _bk-<rand> dir and promote afterward.
set "_bkname="
set "_outrel=%RELEASE_DIR%"
set "_outcfg="
if exist "%RELEASE_DIR%\win-unpacked" rmdir /s /q "%RELEASE_DIR%\win-unpacked" >nul 2>&1
if exist "%RELEASE_DIR%\win-unpacked" (
  echo [WARN] release\win-unpacked is locked by another process
  echo [WARN] using a fresh fallback output dir instead of deleting it
  set "_bkname=_bk-%RANDOM%-%RANDOM%"
)
if defined _bkname (
  set "_outrel=%RELEASE_DIR%\!_bkname!"
  set "_outcfg=../release/!_bkname!"
  echo   Fallback output: !_outrel!
)
if defined _outcfg (
  call npm run dist -- --config.directories.output=!_outcfg!
) else (
  call npm run dist
)
if errorlevel 1 ( echo [FAIL] electron-builder packaging failed, abort & exit /b 1 )

if defined _bkname (
  echo [STEP] Promoting installer from fallback dir to release root ...
  set "_ship=!_outrel!\BempDiff-%VER%-setup.exe"
  if not exist "!_ship!" ( echo [FAIL] no installer in fallback dir: !_ship! & exit /b 1 )
  copy /y "!_ship!" "%RELEASE_DIR%\BempDiff-%VER%-setup.exe" >nul
  if errorlevel 1 ( echo [FAIL] failed to promote installer & exit /b 1 )
  if exist "!_ship!.blockmap" copy /y "!_ship!.blockmap" "%RELEASE_DIR%\BempDiff-%VER%-setup.exe.blockmap" >nul
  echo [OK] Installer promoted to release root.
)

REM ============ 4) Locate installer + notes + publish ============
echo [4/4] Locate installer + notes + publish to GitHub Releases ...
REM Pick latest non-arm64 x64 setup (first in reverse-modified-time order)
set "SETUP_EXE="
for /f "delims=" %%f in ('dir /b /o:-d "%RELEASE_DIR%\BempDiff-*-setup.exe" 2^>nul ^| findstr /v /i "arm64"') do (
  if not defined SETUP_EXE set "SETUP_EXE=%RELEASE_DIR%\%%f"
)
if not defined SETUP_EXE ( echo [FAIL] no BempDiff-*-setup.exe found in release dir & exit /b 1 )

REM Derive version from file name (BempDiff-<ver>-setup.exe -> <ver>)
for %%f in ("%SETUP_EXE%") do set "BASENAME=%%~nf"
set "FILEVER="
for /f "tokens=2 delims=-" %%v in ("%BASENAME%") do set "FILEVER=%%v"
if not defined FILEVER ( echo [FAIL] cannot derive version from %BASENAME% & exit /b 1 )
if defined VER (
  if not "%FILEVER%"=="%VER%" ( echo [FAIL] installer version %FILEVER% != package version %VER% & exit /b 1 )
) else (
  set "VER=%FILEVER%"
)
echo   Installer: %SETUP_EXE%
echo   Version : %VER%

REM Release record: always generate next to the installer (MTask-style)
if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
if not exist "%GEN_NOTES%" ( echo [FAIL] missing gen-release-notes.py & exit /b 1 )
set "NOTES=%RELEASE_DIR%\release-notes-%VER%.md"
echo   Generating release record: %NOTES%
"%PYEXE%" "%GEN_NOTES%" "%VER%" "%NOTES%"
if errorlevel 1 ( echo [FAIL] release record generation failed & exit /b 1 )
echo   Notes  : %NOTES%

if defined GH_DRY_RUN ( echo   [DRY-RUN] no Release will be created / no asset uploaded )

REM Explicitly set version as the Release tag (does not rely on a git tag existing)
set "GH_TAG=%VER%"

REM Publish (token is picked up from git credential store by publish-release.py)
"%PYEXE%" "%PUBLISH_PY%" "%SETUP_EXE%" "%NOTES%" "BempDiff-%VER%-setup.exe"
if errorlevel 1 ( echo [FAIL] publish failed - check network / github.com token in git credentials & exit /b 1 )

echo.
echo [DONE] One-shot publish finished: %SETUP_EXE%
if not defined GH_DRY_RUN echo   Release: https://github.com/wangnan05563/BempDiff/releases/tag/%VER%
endlocal

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
REM Delete stale setup / blockmap before packaging to avoid stale or output-locked files in _bk dirs
if exist "%RELEASE_DIR%" (
  for %%f in ("%RELEASE_DIR%\BempDiff-*-setup.exe" "%RELEASE_DIR%\BempDiff-*-setup.blockmap") do del /q "%%f" >nul 2>&1
)
cd /d "%BEMPDIFF%"
call npm run dist
if errorlevel 1 ( echo [FAIL] electron-builder packaging failed, abort & exit /b 1 )

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
set "VER="
for /f "tokens=2 delims=-" %%v in ("%BASENAME%") do set "VER=%%v"
if not defined VER ( echo [FAIL] cannot derive version from %BASENAME% & exit /b 1 )
echo   Installer: %SETUP_EXE%
echo   Version : %VER%

REM Notes: prefer release-notes-<VER>.md, else auto-generate from git log
set "NOTES=%REPO_ROOT%\release-notes-%VER%.md"
if not exist "%NOTES%" (
  echo   %NOTES% not found, generating notes from git log ...
  set "NOTES=%REPO_ROOT%\release-notes-%VER%.auto.md"
  (
    echo # BempDiff %VER%
    echo.
    echo Auto-generated release notes from git log - replace with official notes before publishing:
    echo.
    git -C "%REPO_ROOT%" log --no-merges --oneline -20 2>nul
  ) > "%NOTES%"
)
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

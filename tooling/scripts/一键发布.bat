@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
REM 本文件为 UTF-8 无 BOM；切到 65001 代码页，避免中文注释/提示在默认 GBK 代码页下乱码
REM ============================================================
REM BempDiff 一键构建打包 + 发布 GitHub Release
REM ------------------------------------------------------------
REM 流程：
REM   1) 安装依赖（bempdiff / webui / dev-shell）
REM   2) 组装 dist_input（编译 jar + jlink 最小 JRE + vite 构建前端并镜像）
REM   3) electron-builder 打 NSIS 安装包（x64，输出到仓库根 release/）
REM   4) 读取说明 md（release-notes-<版本>.md，缺省用 git log 自动生成）
REM      调用 publish-release.py 创建 Release + 上传安装包资产
REM
REM 用法：在 tooling/scripts 目录双击，或在仓库根执行
REM         tooling\scripts\一键发布.bat
REM
REM 安全试运行（仅构建 + 打印将要发布的动作，不真正发到 GitHub）：
REM         set GH_DRY_RUN=1 && tooling\scripts\一键发布.bat
REM
REM 前置：Node + npm、JDK21（build_dist_input.ps1 自动探测 zulu/本机 JDK）、
REM       git（用于推断仓库/标签/分支，并从 git 凭据库取 github.com 令牌）
REM ============================================================

REM ---------- 路径 ----------
set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%..\.."
for %%I in ("%REPO_ROOT%") do set "REPO_ROOT=%%~fI"
set "BEMPDIFF=%REPO_ROOT%\bempdiff"
set "RELEASE_DIR=%REPO_ROOT%\release"
set "PUBLISH_PY=%REPO_ROOT%\tooling\scripts\publish-release.py"

REM ---------- Python 可执行（优先 python，否则 python3）----------
set "PYEXE=python"
where python >nul 2>nul || set "PYEXE=python3"

REM ---------- Electron 国内镜像（提升下载成功率；境外机器可删除这两行）----------
set "ELECTRON_MIRROR=https://registry.npmmirror.com/-/binary/electron/"
set "ELECTRON_BUILDER_BINARIES_MIRROR=https://registry.npmmirror.com/-/binary/electron-builder-binaries/"

cd /d "%REPO_ROOT%"

REM ============ 1) 安装依赖 ============
echo [1/4] 安装依赖（bempdiff / webui / dev-shell）...
cd /d "%BEMPDIFF%"
call npm install --no-audit --no-fund
if errorlevel 1 ( echo [失败] bempdiff 依赖安装失败 & exit /b 1 )
call npm --prefix webui install --no-audit --no-fund
if errorlevel 1 ( echo [失败] webui 依赖安装失败 & exit /b 1 )
call npm --prefix dev-shell install --no-audit --no-fund
if errorlevel 1 ( echo [失败] dev-shell 依赖安装失败 & exit /b 1 )

REM ============ 2) 组装 dist_input（编译 jar + jlink JRE + 前端） ============
echo [2/4] 组装 dist_input（编译 jar + jlink 最小 JRE + 构建前端）...
powershell -NoProfile -ExecutionPolicy Bypass -File "%BEMPDIFF%\scripts\build_dist_input.ps1"
if errorlevel 1 ( echo [失败] 组装 dist_input 失败，终止发布 & exit /b 1 )

REM ============ 3) electron-builder 打 NSIS 安装包（x64） ============
echo [3/4] electron-builder 打 NSIS 安装包（x64）...
REM 出包前清掉旧 setup / blockmap，避免 stale 或 output-locked 落进 _bk 目录
if exist "%RELEASE_DIR%" (
  for %%f in ("%RELEASE_DIR%\BempDiff-*-setup.exe" "%RELEASE_DIR%\BempDiff-*-setup.blockmap") do del /q "%%f" >nul 2>&1
)
cd /d "%BEMPDIFF%"
call npm run dist
if errorlevel 1 ( echo [失败] electron-builder 打包失败，终止发布 & exit /b 1 )

REM ============ 4) 定位安装包 + 生成说明 + 发布 ============
echo [4/4] 定位安装包 + 说明 + 发布到 GitHub Releases...
REM 找最新生成的非 arm64 的 x64 setup（按修改时间倒序取第一个）
set "SETUP_EXE="
for /f "delims=" %%f in ('dir /b /o:-d "%RELEASE_DIR%\BempDiff-*-setup.exe" 2^>nul ^| findstr /v /i "arm64"') do (
  if not defined SETUP_EXE set "SETUP_EXE=%RELEASE_DIR%\%%f"
)
if not defined SETUP_EXE ( echo [失败] 未找到生成的 BempDiff-*-setup.exe（release 目录）& exit /b 1 )

REM 从文件名推导版本号（BempDiff-<版本>-setup.exe -> <版本>）
for %%f in ("%SETUP_EXE%") do set "BASENAME=%%~nf"
set "VER="
for /f "tokens=2 delims=-" %%v in ("%BASENAME%") do set "VER=%%v"
if not defined VER ( echo [失败] 无法从文件名推导版本：%BASENAME% & exit /b 1 )
echo   安装包: %SETUP_EXE%
echo   版本  : %VER%

REM 说明文件：优先 release-notes-<VER>.md，否则用 git log 自动生成
set "NOTES=%REPO_ROOT%\release-notes-%VER%.md"
if not exist "%NOTES%" (
  echo   未找到 %NOTES%，用 git log 自动生成说明...
  set "NOTES=%REPO_ROOT%\release-notes-%VER%.auto.md"
  (
    echo # BempDiff %VER%
    echo.
    echo 自动生成的发布说明（来自 git 提交记录，建议使用前替换为正式说明）：
    echo.
    git -C "%REPO_ROOT%" log --no-merges --oneline -20 2>nul
  ) > "%NOTES%"
)
echo   说明  : %NOTES%

if defined GH_DRY_RUN ( echo   [DRY-RUN] 不会真正创建 Release / 上传资产)

REM 显式把版本号设为 Release 标签（不依赖仓库是否打了 git tag）
set "GH_TAG=%VER%"

REM 发布（token 由 publish-release.py 自动从 git 凭据库读取）
"%PYEXE%" "%PUBLISH_PY%" "%SETUP_EXE%" "%NOTES%" "BempDiff-%VER%-setup.exe"
if errorlevel 1 ( echo [失败] 发布失败，请检查网络 / git 凭据库中的 github.com 令牌 & exit /b 1 )

echo.
echo [完成] 一键发布结束：%SETUP_EXE%
if not defined GH_DRY_RUN echo   Release: https://github.com/wangnan05563/BempDiff/releases/tag/%VER%
endlocal

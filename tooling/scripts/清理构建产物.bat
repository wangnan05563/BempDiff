@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0..\.."

echo [clean-release] Deleting all reproducible build outputs under release\ ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0clean-release.ps1" -Force
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (
  echo [clean-release] Done. Rebuild with tooling\scripts\构建打包.bat when needed.
) else (
  echo [clean-release] Failed with exit code %RC%. Close BempDiff / sidecar and retry.
)
pause
exit /b %RC%

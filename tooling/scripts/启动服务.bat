@echo off
chcp 936 >nul 2>&1
setlocal EnableExtensions
cd /d "%~dp0\..\.."

REM =====================================================================
REM  BempDiff desktop-shell launcher
REM
REM  Self-contained entry point (browser-free, Electron native window).
REM  The Java backend + Electron app are launched detached in the
REM  background; this console is only a startup status panel.
REM
REM  - Closing this window never stops the app; it keeps running in the
REM    background. To quit, close the Electron window or run the stop
REM    batch in this folder (the sibling stop script).
REM  - Passing 'ghost' runs fully silently (for automation, no panel).
REM
REM  ASCII only on purpose: a Chinese filename in a non-GBK terminal is
REM  enough to corrupt parsing, so no CJK literals are used in here.
REM  The backend is started by dev-shell\main.js as a windowless
REM  detached sidecar, which is why it survives this panel closing.
REM =====================================================================

echo ============================================================
echo   BempDiff desktop-shell launcher
echo ============================================================
echo.

echo [STEP 1/2] Checking Java runtime ...

REM ---------- 1. Locate Java runtime (quick sanity check only) ----------
set "JAVA="
if exist "release\dist_input\jre\bin\java.exe" (
  set "JAVA=release\dist_input\jre\bin\java.exe"
) else (
  for /d %%d in ("bempdiff\toolchain\zulu21*") do (
    if not defined JAVA if exist "%%d\bin\java.exe" set "JAVA=%%d\bin\java.exe"
  )
)
if not defined JAVA (
  for /f "usebackq delims=" %%j in (`where java 2^>nul`) do (
    if not defined JAVA set "JAVA=%%j"
  )
)
if not defined JAVA (
  echo [ERROR] Java runtime not found. Run the build script or install JDK21.
  if not "%~1"=="ghost" pause
  exit /b 1
)
echo [OK] Java runtime: %JAVA%

REM ---------- 2. Locate Electron runtime ----------
echo [STEP 2/2] Checking Electron runtime ...
set "ELECTRON_BIN="
if exist "%~dp0..\..\bempdiff\dev-shell\node_modules\.bin\electron.cmd" set "ELECTRON_BIN=%~dp0..\..\bempdiff\dev-shell\node_modules\.bin\electron.cmd"
if not defined ELECTRON_BIN (
  for /f "usebackq delims=" %%e in (`where electron 2^>nul`) do (
    if not defined ELECTRON_BIN set "ELECTRON_BIN=%%e"
  )
)
if not defined ELECTRON_BIN (
  echo [ERROR] Electron runtime not found. Run:  cd bempdiff\dev-shell ^&^& npm install
  if not "%~1"=="ghost" pause
  exit /b 1
)
echo [OK] Electron runtime: %ELECTRON_BIN%
echo.

REM ---------- 3. Launch Electron in its own hidden console ----------
set "BEMPDIFF_FRONTEND=build"
set "SHELL_ABS=%~dp0..\..\bempdiff\dev-shell"
set "ELECTRON_EXE=%SHELL_ABS%\node_modules\electron\dist\electron.exe"
echo [START] Starting backend + Electron window in the background ...
REM Start the Electron GUI directly as its own process (GUI apps have no
REM console, so no black window flashes and nothing needs hiding). Do NOT
REM use -WindowStyle Hidden: it would hide the app window too. Launching
REM it via Start-Process (not `start /B`) keeps it off this panel's console,
REM so the panel can close freely while the app keeps running.
REM The child inherits the env vars set above: BEMPDIFF_FRONTEND=build
REM makes main.js skip vite and serve webui\dist. Do NOT set
REM BEMPDIFF_DEV_URL, or main.js treats it as "external server already
REM running" and skips starting the backend sidecar.
if not exist "%ELECTRON_EXE%" (
  echo [ERROR] electron.exe not found. Run:  cd bempdiff\dev-shell ^&^& npm install
  if not "%~1"=="ghost" pause
  exit /b 1
)
powershell -NoProfile -Command "Start-Process -FilePath '%ELECTRON_EXE%' -ArgumentList '.' -WorkingDirectory '%SHELL_ABS%'"

REM ghost == fully silent: automation path leaves no panel behind
if "%~1"=="ghost" exit /b 0

echo.
echo   The app is running in the background.
echo   Closing this window does NOT stop it.
echo   To quit: close the Electron window, or run the stop script
echo   in this folder's sibling batch (tooling\scripts).
echo.
echo   Press any key to close this window (app keeps running in background) ...
pause >nul
exit /b 0
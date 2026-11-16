@echo off
rem FR-005 CLI entry for CI gating.
rem Exit codes: 0=no diff, 1=diff found (with --fail-on-diff), 2=usage or runtime error.
setlocal
set "SCRIPT_DIR=%~dp0"
set "CP="
set "DEPS=;%SCRIPT_DIR%..\cfr.jar;%SCRIPT_DIR%..\toolchain\lib\*"
rem Published fat jar first (self-contained; extra DEPS entries are harmless duplicates).
if exist "%SCRIPT_DIR%..\..\release\dist_input\app\bempdiff.jar" set "CP=%SCRIPT_DIR%..\..\release\dist_input\app\bempdiff.jar"
if "%CP%"=="" if exist "%SCRIPT_DIR%..\..\release\java_core\out\com\bempdiff\Main.class" set "CP=%SCRIPT_DIR%..\..\release\java_core\out"
if defined BEMPDIFF_JAR set "CP=%BEMPDIFF_JAR%"
if "%CP%"=="" (
  echo error: bempdiff.jar not found. Set BEMPDIFF_JAR or build java_core first.
  exit /b 2
)
set "JAVA_EXE=java"
if defined JAVA21_HOME if exist "%JAVA21_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA21_HOME%\bin\java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -cp "%CP%%DEPS%" com.bempdiff.Main %*
exit /b %ERRORLEVEL%

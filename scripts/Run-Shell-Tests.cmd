@echo off
rem CWLauncher v3 shell tests, run on the bundled Java (no JDK needed).
rem ShellCheck: build store, core versions, catalog parsing, installer (offline).
rem ShellFlow : the whole Create Build form incl. validation (needs a display).
setlocal
cd /d "%~dp0"
set "CP=app\CWLauncher.jar;tests\shell-tests.jar"
"runtime\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%CP%" ShellCheck
if errorlevel 1 exit /b 1
"runtime\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%CP%" ShellFlow
if errorlevel 1 exit /b 1
echo.
echo ALL SHELL TESTS PASSED
endlocal

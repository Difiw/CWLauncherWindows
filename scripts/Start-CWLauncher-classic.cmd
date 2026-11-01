@echo off
rem Old Common World window (previous version).
setlocal
cd /d "%~dp0"
start "" "runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -Xmx512m -cp "app\CWLauncher.jar" ru.cw.launcher.Main
endlocal

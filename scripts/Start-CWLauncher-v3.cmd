@echo off
rem Experimental v3 shell (build manager). CWLauncher.exe starts the full launcher.
setlocal
cd /d "%~dp0"
start "" "runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -Xmx512m -cp "app\CWLauncher.jar" ru.cw.launcher.shell.ShellMain
endlocal

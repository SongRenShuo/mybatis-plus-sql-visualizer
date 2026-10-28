@echo off
rem Run a recording scene script elevated (the sandbox IDE is an elevated
rem process, so a non-elevated SendInput is blocked by UIPI).
rem Usage: _run_elev.bat <scene_xxx.py>
cd /d %~dp0
echo ==== run %1 at %DATE% %TIME% ==== > _elev_run.log
python "%~1" >> _elev_run.log 2>&1
set "SCENE_EXIT=%ERRORLEVEL%"
echo ==== exit %SCENE_EXIT% ==== >> _elev_run.log
exit /b %SCENE_EXIT%

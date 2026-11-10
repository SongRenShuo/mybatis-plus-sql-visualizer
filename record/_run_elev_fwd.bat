@echo off
rem Forward-args variant of _run_elev.bat: %1 = scene script, %2.. = args.
cd /d %~dp0
echo ==== run %1 at %DATE% %TIME% ==== > _elev_run.log
python "%~1" %2 %3 %4 %5 %6 %7 >> _elev_run.log 2>&1
set "SCENE_EXIT=%ERRORLEVEL%"
echo ==== exit %SCENE_EXIT% ==== >> _elev_run.log
exit /b %SCENE_EXIT%

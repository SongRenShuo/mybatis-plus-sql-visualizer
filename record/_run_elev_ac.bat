@echo off
rem Run an AC probe script elevated (same rationale as _run_elev.bat, but with a
rem separate log so the master session's _elev_run.log is never clobbered).
rem Usage: _run_elev_ac.bat <ac_xxx.py>
cd /d %~dp0
echo ==== run %1 at %DATE% %TIME% ==== > _elev_ac.log
python "%~1" %2 %3 %4 %5 >> _elev_ac.log 2>&1
set "AC_EXIT=%ERRORLEVEL%"
echo ==== exit %AC_EXIT% ==== >> _elev_ac.log
exit /b %AC_EXIT%

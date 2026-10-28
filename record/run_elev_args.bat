@echo off
rem 多参数版提权封装（_run_elev.bat 只接受一个参数）
cd /d %~dp0
echo ==== %DATE% %TIME% : %* ==== > _elev_args.log
python %* >> _elev_args.log 2>&1
set "RC=%ERRORLEVEL%"
echo ==== exit %RC% ==== >> _elev_args.log
exit /b %RC%

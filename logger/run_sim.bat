@echo off
rem Test run with the built-in simulator (no car needed). Press keys g/b/a/s/h/c/i/m, q to quit.
set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"
cd /d "%~dp0"
"%PY%" elm_logger.py --sim --sim-speed 10
pause

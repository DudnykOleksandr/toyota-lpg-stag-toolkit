@echo off
set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"
cd /d "%~dp0"
"%PY%" elm_logger.py --list
echo.
"%PY%" elm_logger.py --probe %*
pause

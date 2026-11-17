@echo off
rem Find Python (installed per-user by winget) and run the ELM logger.
set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"
cd /d "%~dp0"
"%PY%" elm_logger.py %*
pause

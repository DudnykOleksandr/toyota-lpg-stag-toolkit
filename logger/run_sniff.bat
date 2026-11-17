@echo off
rem Stag protocol sniffer: AC STAG <-> com0com pair <-> this script <-> Stag (Bluetooth COM or USB cable).
rem Usage: run_sniff.bat COM9            (Bluetooth COM of the Stag interface; AC STAG connects to COM7)
rem        run_sniff.bat COM9 COM8       (free end of the com0com pair COM7<->COM8)
rem Type notes + Enter while it runs; Ctrl+C to stop. Log: logs\stag_sniff_*.log
set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"
if "%~1"=="" (
  echo Usage: run_sniff.bat REAL_COM [VIRT_COM, default COM8]
  "%PY%" -m serial.tools.list_ports -v
  pause
  exit /b 1
)
set "VIRT=%~2"
if "%VIRT%"=="" set "VIRT=COM8"
cd /d "%~dp0"
"%PY%" stag_sniff.py --real %1 --virt %VIRT%
pause

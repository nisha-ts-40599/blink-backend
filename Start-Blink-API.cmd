@echo off
cd /d "%~dp0"
set PATH=C:\Program Files\Go\bin;%PATH%
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\..\scripts\_start-go-backend.ps1" -BackendRoot "%~dp0."
if errorlevel 1 (
  echo.
  echo Blink API did not start. If Application Control blocked blink-api.exe, run as Administrator:
  echo   powershell -ExecutionPolicy Bypass -File "%~dp0..\..\scripts\Install-BlinkLocalTrust.ps1"
  echo then reboot once.
  pause
  exit /b 1
)

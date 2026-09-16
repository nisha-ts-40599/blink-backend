@echo off
cd /d "%~dp0"
set PATH=C:\Program Files\Go\bin;%PATH%
echo Starting Blink Go API on :8090 ...
"%~dp0bin\blink-api.exe"
pause

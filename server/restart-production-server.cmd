@echo off
setlocal

REM Start in this script's folder so it works when opened by double-clicking.
cd /d "%~dp0"

echo Restarting the Simple App production sync server...
echo.
docker compose -f compose.yaml -f compose.dual.yaml up -d --force-recreate sync-prod

if errorlevel 1 (
    echo.
    echo [Failed] The production server was not restarted.
    echo Check that Docker Desktop is running, then try again.
    pause
    exit /b 1
)

echo.
echo [Done] The production sync server has been restarted.
docker compose -f compose.yaml -f compose.dual.yaml ps sync-prod
echo.
pause

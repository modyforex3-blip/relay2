@echo off
title GSM Cloud Relay - 24/7 Auto Guardian
cd /d "%~dp0"

set NODE_EXE=node.exe
where node.exe >nul 2>&1
if errorlevel 1 (
    if exist "C:\Program Files\nodejs\node.exe" (
        set NODE_EXE="C:\Program Files\nodejs\node.exe"
    )
)

echo [%date% %time%] Checking GSM Relay Server... >> "%~dp0relay_service.log"

:: Check if server.js is running on port 5050
netstat -ano | findstr :5050 >nul 2>&1
if errorlevel 1 (
    echo [%date% %time%] Starting server.js on port 5050... >> "%~dp0relay_service.log"
    start "GSM Relay Server" /b %NODE_EXE% "%~dp0web-terminal\server.js"
) else (
    echo [%date% %time%] server.js is already running on port 5050. >> "%~dp0relay_service.log"
)

timeout /t 2 /nobreak >nul

:: Check if tunnel-keeper is running
tasklist /FI "IMAGENAME eq node.exe" /V 2>nul | findstr /I "tunnel-keeper" >nul 2>&1
if errorlevel 1 (
    echo [%date% %time%] Starting tunnel-keeper.js... >> "%~dp0relay_service.log"
    start "GSM Tunnel Keeper" /b %NODE_EXE% "%~dp0tunnel-keeper.js"
) else (
    echo [%date% %time%] tunnel-keeper.js is already active. >> "%~dp0relay_service.log"
)

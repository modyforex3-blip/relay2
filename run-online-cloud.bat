@echo off
title GSM Cloud Relay - Online Server & Tunnel
cd /d "%~dp0"

echo ================================================================
echo    Starting Local Relay Server (Port 5050)...
echo ================================================================
start "GSM Relay Server" /b node "%~dp0web-terminal\server.js"

timeout /t 2 /nobreak >nul

echo ================================================================
echo    Starting Global Online Cloud Tunnel (WSS + HTTPS)...
echo ================================================================
echo    Your online cloud terminal will be available worldwide!
echo ================================================================
"%~dp0cloudflared.exe" tunnel --url http://localhost:5050
pause

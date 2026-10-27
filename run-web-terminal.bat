@echo off
title GSM Cloud Web Terminal
cd /d "%~dp0web-terminal"
echo =======================================================
echo    Starting GSM Cloud Web Terminal & Local Relay...
echo =======================================================
node server.js
pause

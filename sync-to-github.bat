@echo off
chcp 65001 >nul
title GSM Relay Auto-Sync to GitHub
echo ==============================================================
echo    🚀 بدء المزامنة التلقائية مع GitHub و Render...
echo ==============================================================
powershell -ExecutionPolicy Bypass -File "%~dp0sync-to-github.ps1" %*
echo.
echo اضغط أي مفتاح للإغلاق...
pause >nul

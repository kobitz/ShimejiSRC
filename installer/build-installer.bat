@echo off
:: Builds dist\ShimejiSRC-Setup-<version>.exe. Usage: installer\build-installer.bat [version]
:: Prerequisites: a finished Release build in your install folder (run Build.bat -> [1] first, so
:: Shimeji.exe / Shimeji-ee.jar are fresh) and Inno Setup 6.3+ (https://jrsoftware.org/isinfo.php).
setlocal
set "VERSION=%~1"
if "%VERSION%"=="" set "VERSION=1.10"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-installer.ps1" -Version "%VERSION%"
if errorlevel 1 ( echo. & echo *** INSTALLER BUILD FAILED *** & pause & exit /b 1 )
pause

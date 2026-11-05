@echo off
setlocal

:: Machine-specific paths live in Build.local.bat (gitignored).
:: Copy Build.local.bat.example to Build.local.bat and edit it for your machine.
if exist "%~dp0Build.local.bat" call "%~dp0Build.local.bat"

:: Defaults when not set by Build.local.bat (or the environment).
:: WORKSPACE defaults to the folder this script lives in.
if not defined WORKSPACE set "WORKSPACE=%~dp0"

:: Verify configured paths exist (silent unless something is missing)
if defined LAUNCH4J if not exist "%LAUNCH4J%" echo WARNING: launch4jc.exe not found at: %LAUNCH4J%
if defined CONFIG_TEST if not exist "%CONFIG_TEST%" echo WARNING: configTest.xml not found at: %CONFIG_TEST%
if defined CONFIG_RELEASE if not exist "%CONFIG_RELEASE%" echo WARNING: config.xml not found at: %CONFIG_RELEASE%

:: Ask which build
echo ================================
echo  Shimeji Fork Build
echo ================================
echo  [1] Release build
echo  [2] Test build (default)
echo  [3] Ant only (no exe)
echo ================================
set /p CHOICE=Choose (1/2/3):

cd /d "%WORKSPACE%"

echo.
echo Running Ant...
echo --------------------------------
call ant jar
if errorlevel 1 (
    echo.
    echo *** BUILD FAILED ***
    pause
    exit /b 1
)

echo.
echo Ant build successful!

if "%CHOICE%"=="3" (
    echo Skipping launch4j. Done!
    pause
    exit /b 0
)

if not defined LAUNCH4J (
    echo.
    echo LAUNCH4J is not set - skipping exe wrapping. See Build.local.bat.example.
    pause
    exit /b 0
)

if "%CHOICE%"=="1" (
    set CONFIG=%CONFIG_RELEASE%
    echo Running launch4j with RELEASE config...
) else (
    set CONFIG=%CONFIG_TEST%
    echo Running launch4j with TEST config...
)

if not defined CONFIG (
    echo.
    echo *** Launch4j config path is not set. See Build.local.bat.example. ***
    pause
    exit /b 1
)

"%LAUNCH4J%" "%CONFIG%"
if errorlevel 1 (
    echo.
    echo *** LAUNCH4J FAILED ***
    pause
    exit /b 1
)

echo.
echo ================================
echo  Build complete!
echo ================================

if "%CHOICE%"=="1" (
    set EXE_NAME=Shimeji.exe
) else (
    set EXE_NAME=ShimejiTest.exe
)

if not defined INSTALL_DIR exit /b 0

set /p RUNCHOICE=Press 1 to launch %EXE_NAME%, or Enter to close:
if "%RUNCHOICE%"=="1" (
    echo Launching %EXE_NAME%...
    start "" "%INSTALL_DIR%\%EXE_NAME%"
)
exit /b 0

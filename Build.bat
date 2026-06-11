@echo off
setlocal

set WORKSPACE=D:\Downloads\Shimeji Workspace\Stable
set INSTALL_DIR=C:\Users\ko\Desktop\Mario Install Testing
set LAUNCH4J=D:\Downloads\Shimeji Workspace\exe builder launch4j\launch4j\launch4jc.exe
set CONFIG_RELEASE=D:\Downloads\Shimeji Workspace\exe builder launch4j\config.xml
set CONFIG_TEST=D:\Downloads\Shimeji Workspace\exe builder launch4j\configTest.xml

:: Verify paths exist (silent unless something is missing)
if not exist "%LAUNCH4J%" echo WARNING: launch4jc.exe not found at: %LAUNCH4J%
if not exist "%CONFIG_TEST%" echo WARNING: configTest.xml not found at: %CONFIG_TEST%
if not exist "%CONFIG_RELEASE%" echo WARNING: config.xml not found at: %CONFIG_RELEASE%

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

if "%CHOICE%"=="1" (
    set CONFIG=%CONFIG_RELEASE%
    echo Running launch4j with RELEASE config...
) else (
    set CONFIG=%CONFIG_TEST%
    echo Running launch4j with TEST config...
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

set /p RUNCHOICE=Press 1 to launch %EXE_NAME%, or Enter to close:
if "%RUNCHOICE%"=="1" (
    echo Launching %EXE_NAME%...
    start "" "%INSTALL_DIR%\%EXE_NAME%"
)
exit /b 0

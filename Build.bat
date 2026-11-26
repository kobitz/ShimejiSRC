@echo off
setlocal EnableExtensions

:: ---------------------------------------------------------------------------
:: Shimeji fork build.
::
::   [4] Portable build (works on any machine): builds the jar with Ant, finds or
::       downloads Launch4j, and produces a runnable dist\koaibo\Shimeji.exe
::       (with conf\ and img\ beside it). Needs only JDK 17+ and Apache Ant.
::   [1][2] Release / Test builds: your own Launch4j configs and install folder,
::       set in Build.local.bat (gitignored; template: Build.local.bat.example).
::   [3] Ant only (target\Shimeji-ee.jar).
::
:: Build.local.bat is optional. WORKSPACE defaults to the folder this script is in.
:: ---------------------------------------------------------------------------
if exist "%~dp0Build.local.bat" call "%~dp0Build.local.bat"
if not defined WORKSPACE set "WORKSPACE=%~dp0"

set "ROOT=%WORKSPACE%"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "HAVE_LOCAL="
if defined LAUNCH4J if defined CONFIG_RELEASE if defined CONFIG_TEST set "HAVE_LOCAL=1"

:: Verify configured paths exist (silent unless something is missing)
if defined LAUNCH4J if not exist "%LAUNCH4J%" echo WARNING: launch4jc.exe not found at: %LAUNCH4J%
if defined CONFIG_TEST if not exist "%CONFIG_TEST%" echo WARNING: configTest.xml not found at: %CONFIG_TEST%
if defined CONFIG_RELEASE if not exist "%CONFIG_RELEASE%" echo WARNING: config.xml not found at: %CONFIG_RELEASE%

set "DEFAULT=4"
if defined HAVE_LOCAL set "DEFAULT=2"

echo ================================
echo  Shimeji Fork Build
echo ================================
echo  [1] Release build  (your Launch4j config)
echo  [2] Test build     (your Launch4j config)
echo  [3] Ant only (jar, no exe)
echo  [4] Portable build (exe in dist\koaibo)
echo ================================
set "CHOICE="
set /p CHOICE=Choose (1/2/3/4, Enter = %DEFAULT%):
if not defined CHOICE set "CHOICE=%DEFAULT%"

if "%CHOICE%"=="1" if not defined HAVE_LOCAL goto :need_local
if "%CHOICE%"=="2" if not defined HAVE_LOCAL goto :need_local

cd /d "%ROOT%"

:: ---- tools check -----------------------------------------------------------
where ant >nul 2>nul
if errorlevel 1 goto :need_ant
where javac >nul 2>nul
if errorlevel 1 goto :need_jdk

echo.
echo Running Ant...
echo --------------------------------
call ant jar
if errorlevel 1 goto :ant_failed
echo.
echo Ant build successful!

if "%CHOICE%"=="3" goto :ant_only
if "%CHOICE%"=="4" goto :portable
goto :your_launch4j

:: ---- [1]/[2]: your own Launch4j configs ----------------------------------------
:your_launch4j
if "%CHOICE%"=="1" (
    set "CONFIG=%CONFIG_RELEASE%"
    set "EXE_NAME=Shimeji.exe"
    echo Running launch4j with RELEASE config...
) else (
    set "CONFIG=%CONFIG_TEST%"
    set "EXE_NAME=ShimejiTest.exe"
    echo Running launch4j with TEST config...
)
"%LAUNCH4J%" "%CONFIG%"
if errorlevel 1 goto :l4j_failed

echo.
echo ================================
echo  Build complete!
echo ================================
if not defined INSTALL_DIR exit /b 0
set "RUNCHOICE="
set /p RUNCHOICE=Press 1 to launch %EXE_NAME%, or Enter to close:
if "%RUNCHOICE%"=="1" (
    echo Launching %EXE_NAME%...
    start "" "%INSTALL_DIR%\%EXE_NAME%"
)
exit /b 0

:: ---- [3] ----------------------------------------------------------------------------
:ant_only
echo Skipping launch4j. The jar is target\Shimeji-ee.jar
pause
exit /b 0

:: ---- [4] portable build -----------------------------------------------------------
:portable
set "DIST=%ROOT%\dist\koaibo"
set "L4J="
if defined LAUNCH4J if exist "%LAUNCH4J%" set "L4J=%LAUNCH4J%"
if not defined L4J if exist "%ROOT%\tools\launch4j\launch4jc.exe" set "L4J=%ROOT%\tools\launch4j\launch4jc.exe"
if not defined L4J if exist "%ProgramFiles(x86)%\Launch4j\launch4jc.exe" set "L4J=%ProgramFiles(x86)%\Launch4j\launch4jc.exe"
if not defined L4J if exist "%ProgramFiles%\Launch4j\launch4jc.exe" set "L4J=%ProgramFiles%\Launch4j\launch4jc.exe"
if not defined L4J goto :get_launch4j
:have_launch4j
echo Using Launch4j: %L4J%

if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%DIST%"
if errorlevel 1 goto :dist_failed
copy /y "%ROOT%\target\Shimeji-ee.jar" "%DIST%\Shimeji-ee.jar" >nul
xcopy "%ROOT%\conf" "%DIST%\conf\" /e /i /y /q >nul
if exist "%ROOT%\img" xcopy "%ROOT%\img" "%DIST%\img\" /e /i /y /q >nul
if exist "%ROOT%\sound" xcopy "%ROOT%\sound" "%DIST%\sound\" /e /i /y /q >nul
if exist "%ROOT%\whisper_server.py" copy /y "%ROOT%\whisper_server.py" "%DIST%\" >nul
if exist "%ROOT%\whisper_prompt.txt" copy /y "%ROOT%\whisper_prompt.txt" "%DIST%\" >nul
if exist "%ROOT%\TempSensor.exe" copy /y "%ROOT%\TempSensor.exe" "%DIST%\" >nul

:: Use a bundled jre\ if the repo folder has one, else the Java installed on the machine (17+).
set "JRE_PATH="
if exist "%ROOT%\jre\bin\javaw.exe" (
    xcopy "%ROOT%\jre" "%DIST%\jre\" /e /i /y /q >nul
    set "JRE_PATH=jre"
)

set "L4JXML=%ROOT%\dist\launch4j-portable.xml"
> "%L4JXML%" echo ^<launch4jConfig^>
>> "%L4JXML%" echo   ^<dontWrapJar^>false^</dontWrapJar^>
>> "%L4JXML%" echo   ^<headerType^>gui^</headerType^>
>> "%L4JXML%" echo   ^<jar^>%ROOT%\target\Shimeji-ee.jar^</jar^>
>> "%L4JXML%" echo   ^<outfile^>%DIST%\Shimeji.exe^</outfile^>
>> "%L4JXML%" echo   ^<errTitle^>Shimeji^</errTitle^>
>> "%L4JXML%" echo   ^<chdir^>.^</chdir^>
>> "%L4JXML%" echo   ^<priority^>normal^</priority^>
>> "%L4JXML%" echo   ^<downloadUrl^>https://adoptium.net/temurin/releases/?version=17^</downloadUrl^>
>> "%L4JXML%" echo   ^<stayAlive^>false^</stayAlive^>
>> "%L4JXML%" echo   ^<restartOnCrash^>false^</restartOnCrash^>
if exist "%ROOT%\img\icon.ico" >> "%L4JXML%" echo   ^<icon^>%ROOT%\img\icon.ico^</icon^>
>> "%L4JXML%" echo   ^<jre^>
>> "%L4JXML%" echo     ^<path^>%JRE_PATH%^</path^>
>> "%L4JXML%" echo     ^<minVersion^>17^</minVersion^>
>> "%L4JXML%" echo     ^<jdkPreference^>preferJre^</jdkPreference^>
>> "%L4JXML%" echo   ^</jre^>
>> "%L4JXML%" echo ^</launch4jConfig^>

"%L4J%" "%L4JXML%"
if errorlevel 1 goto :l4j_failed
if not exist "%DIST%\Shimeji.exe" goto :l4j_failed

echo.
echo ================================
echo  Build complete!
echo  %DIST%\Shimeji.exe
echo ================================
echo  The whole dist\koaibo folder is the app: zip it or copy it anywhere.
set "RUNCHOICE="
set /p RUNCHOICE=Press 1 to launch it, or Enter to close:
if "%RUNCHOICE%"=="1" start "" /d "%DIST%" "%DIST%\Shimeji.exe"
exit /b 0

:get_launch4j
echo.
echo Launch4j (turns the jar into an exe) was not found.
echo It will be downloaded from SourceForge into tools\launch4j (about 15 MB).
set "ANS="
set /p ANS=Download it now? (y/N):
if /i not "%ANS%"=="y" goto :manual_launch4j
if not exist "%ROOT%\tools" mkdir "%ROOT%\tools"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -UseBasicParsing -Uri 'https://downloads.sourceforge.net/project/launch4j/launch4j-3/3.50/launch4j-3.50-win32.zip' -OutFile '%ROOT%\tools\launch4j.zip'; Expand-Archive -Force -Path '%ROOT%\tools\launch4j.zip' -DestinationPath '%ROOT%\tools'; exit 0 } catch { Write-Host $_; exit 1 }"
if exist "%ROOT%\tools\launch4j.zip" del "%ROOT%\tools\launch4j.zip" >nul 2>nul
if exist "%ROOT%\tools\launch4j\launch4jc.exe" (
    set "L4J=%ROOT%\tools\launch4j\launch4jc.exe"
    goto :have_launch4j
)
:manual_launch4j
echo.
echo Could not get Launch4j automatically. Install it from https://launch4j.sourceforge.net
echo (or unzip it to tools\launch4j so tools\launch4j\launch4jc.exe exists) and run this again.
echo The jar is already built: target\Shimeji-ee.jar  (run it with: java -jar target\Shimeji-ee.jar)
pause
exit /b 1

:: ---- errors -----------------------------------------------------------------------
:need_local
echo.
echo Options 1 and 2 use your own Launch4j configs from Build.local.bat, which is not set up.
echo Choose 4 (portable build), or copy Build.local.bat.example to Build.local.bat and edit it.
pause
exit /b 1

:need_ant
echo.
echo *** Apache Ant was not found on PATH. ***
echo Download it from https://ant.apache.org/bindownload.cgi, unzip it, and add its bin folder to PATH.
pause
exit /b 1

:need_jdk
echo.
echo *** A JDK (javac) was not found on PATH. ***
echo Install JDK 17 or newer (for example Temurin from https://adoptium.net), then reopen this window.
pause
exit /b 1

:ant_failed
echo.
echo *** BUILD FAILED ***
pause
exit /b 1

:l4j_failed
echo.
echo *** LAUNCH4J FAILED ***
pause
exit /b 1

:dist_failed
echo.
echo *** Could not create %DIST% ***
pause
exit /b 1

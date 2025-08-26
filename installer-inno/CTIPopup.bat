@echo off
setlocal enabledelayedexpansion

:: Get the directory where this batch file is located
set "APP_DIR=%~dp0"

:: Remove trailing backslash if present
if "!APP_DIR:~-1!"=="\" set "APP_DIR=!APP_DIR:~0,-1!"

:: Set the Java path
set "JAVA_EXE=!APP_DIR!\jre\bin\javaw.exe"

:: Check if bundled Java exists
if not exist "!JAVA_EXE!" (
    echo Error: Bundled Java Runtime not found at: !JAVA_EXE!
    echo Please ensure the application is properly installed.
    pause
    exit /b 1
)

:: Set classpath
set "CLASSPATH=!APP_DIR!\ctippopup.jar;!APP_DIR!\jtapi.jar"

:: Launch the application
cd /d "!APP_DIR!"
"!JAVA_EXE!" -Xmx512m -cp "!CLASSPATH!" JTAPIGui

:: If we get here, the Java process has ended
exit /b %ERRORLEVEL%

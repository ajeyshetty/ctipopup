@echo off
setlocal enabledelayedexpansion

:: CTI Popup Launcher - Tries multiple methods to run the application
echo Starting LKQ CTI Popup...

:: Method 1: Try bundled JRE
if exist "jre\bin\javaw.exe" (
    echo Using bundled JRE...
    "jre\bin\javaw.exe" -Xmx512m -cp "ctippopup.jar;jtapi.jar" JTAPIGui
    goto :end
)

:: Method 2: Try system Java
java -version >nul 2>&1
if %errorlevel% equ 0 (
    echo Using system Java...
    java -Xmx512m -cp "ctippopup.jar;jtapi.jar" JTAPIGui
    goto :end
)

:: Method 3: Try javaw from PATH
javaw -version >nul 2>&1
if %errorlevel% equ 0 (
    echo Using javaw from PATH...
    javaw -Xmx512m -cp "ctippopup.jar;jtapi.jar" JTAPIGui
    goto :end
)

:: If all methods fail, show error
echo ERROR: Could not find Java Runtime Environment!
echo Please install Java or ensure the bundled JRE is present.
pause

:end
exit /b %errorlevel%

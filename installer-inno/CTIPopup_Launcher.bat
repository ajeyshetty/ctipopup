@echo off
rem LKQ CTI Popup - Robust Multi-Method Launcher
rem This script attempts multiple methods to launch the CTI Popup application

cd /d "%~dp0"

rem Method 1: Try to run the VBS script (silent, no console window)
if exist "CTIPopup.vbs" (
    echo Attempting to launch via VBS script...
    cscript //NoLogo //B "CTIPopup.vbs" >nul 2>&1
    if %ERRORLEVEL% EQU 0 (
        echo VBS launch successful
        goto :end
    )
    echo VBS launch failed with error %ERRORLEVEL%
)

rem Method 2: Try to run the EXE wrapper (if available)
if exist "CTIPopup.exe" (
    echo Attempting to launch via EXE wrapper...
    start "" "CTIPopup.exe"
    if %ERRORLEVEL% EQU 0 (
        echo EXE launch successful
        goto :end
    )
    echo EXE launch failed with error %ERRORLEVEL%
)

rem Method 3: Try to run via batch script
if exist "CTIPopup.bat" (
    echo Attempting to launch via batch script...
    call "CTIPopup.bat"
    if %ERRORLEVEL% EQU 0 (
        echo Batch launch successful
        goto :end
    )
    echo Batch launch failed with error %ERRORLEVEL%
)

rem Method 4: Direct Java execution as fallback
if exist "jre\bin\java.exe" (
    echo Attempting direct Java execution...
    start "" "jre\bin\java.exe" -cp "ctippopup.jar;jtapi.jar" JTAPIGui
    if %ERRORLEVEL% EQU 0 (
        echo Direct Java launch successful
        goto :end
    )
    echo Direct Java launch failed with error %ERRORLEVEL%
)

rem If all methods fail, show error
echo ERROR: All launch methods failed. Please check your installation.
pause

:end

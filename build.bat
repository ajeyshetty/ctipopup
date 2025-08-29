@echo off
REM CTI Popup JTAPI - Production Build Script
REM This script compiles the Java source files and creates a production-ready JAR

echo ========================================
echo CTI Popup JTAPI - Production Build
echo ========================================

REM Create necessary directories
if not exist "bin" mkdir bin
if not exist "dist" mkdir dist

echo.
echo Cleaning previous build...
if exist "bin\*" del /Q bin\*
if exist "dist\*" del /Q dist\*

echo.
echo Compiling Java sources...
javac -cp ".;lib\jtapi.jar" -d bin -sourcepath src src\*.java

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Compilation failed!
    pause
    exit /b 1
)

echo.
echo Creating JAR manifest...
echo Main-Class: JTAPIGui> manifest.txt
echo Class-Path: lib/jtapi.jar>> manifest.txt

echo.
echo Creating production JAR...
jar cfm dist\cti-popup-jtapi.jar manifest.txt -C bin .

echo.
echo Build completed successfully!
echo Production JAR: dist\cti-popup-jtapi.jar
echo.
echo To run the application:
echo java -jar dist\cti-popup-jtapi.jar
echo.
pause

# CTI Popup JTAPI - Production Build Script
# This script compiles the Java source files and creates a production-ready JAR

param(
    [switch]$Clean,
    [switch]$NoJar
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "CTI Popup JTAPI - Production Build" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Create necessary directories
if (!(Test-Path "bin")) { New-Item -ItemType Directory -Path "bin" | Out-Null }
if (!(Test-Path "dist")) { New-Item -ItemType Directory -Path "dist" | Out-Null }

Write-Host ""
Write-Host "Cleaning previous build..." -ForegroundColor Yellow
if (Test-Path "bin\*") { Remove-Item "bin\*" -Recurse -Force }
if (Test-Path "dist\*") { Remove-Item "dist\*" -Recurse -Force }

if ($Clean) {
    Write-Host "Clean completed." -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "Compiling Java sources..." -ForegroundColor Yellow
$compileResult = & javac -cp ".;lib\jtapi.jar" -d bin -sourcepath src src\*.java 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Compilation failed!" -ForegroundColor Red
    Write-Host $compileResult
    exit 1
}

Write-Host "Compilation successful." -ForegroundColor Green

if (!$NoJar) {
    Write-Host ""
    Write-Host "Creating JAR manifest..." -ForegroundColor Yellow
    @"
Main-Class: JTAPIGui
Class-Path: lib/jtapi.jar
"@ | Out-File -FilePath manifest.txt -Encoding ASCII

    Write-Host ""
    Write-Host "Creating production JAR..." -ForegroundColor Yellow
    $jarResult = & jar cfm dist\cti-popup-jtapi.jar manifest.txt -C bin . 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "ERROR: JAR creation failed!" -ForegroundColor Red
        Write-Host $jarResult
        exit 1
    }

    Write-Host "JAR creation successful." -ForegroundColor Green
}

Write-Host ""
Write-Host "Build completed successfully!" -ForegroundColor Green
Write-Host "Production JAR: dist\cti-popup-jtapi.jar" -ForegroundColor Cyan
Write-Host ""
Write-Host "To run the application:" -ForegroundColor Cyan
Write-Host "java -jar dist\cti-popup-jtapi.jar" -ForegroundColor White
Write-Host ""
Write-Host "Or run with GUI:" -ForegroundColor Cyan
Write-Host "java -cp `"bin;lib/jtapi.jar`" JTAPIGui" -ForegroundColor White

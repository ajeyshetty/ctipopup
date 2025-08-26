Write-Host "=== CTI Popup Installer Builder ==="
Write-Host "Building 64-bit installer..."

$installerDir = "C:\Users\axshetty\OneDrive - LKQ\Projects\CTIPopup-JTAPI\installer-inno"
$jreDir = Join-Path $installerDir "jre"

# Download 64-bit JRE
Write-Host "Downloading 64-bit JRE..."
$jreUrl = "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.25%2B9/OpenJDK11U-jre_x64_windows_hotspot_11.0.25_9.zip"
$tmpZip = Join-Path $env:TEMP "jre11-x64.zip"

try {
    if (Test-Path $jreDir) {
        Remove-Item -Recurse -Force $jreDir
        Write-Host "Removed existing JRE folder"
    }
    
    Invoke-WebRequest -Uri $jreUrl -OutFile $tmpZip -UseBasicParsing
    Write-Host "Downloaded JRE to $tmpZip"
    
    # Extract
    $tmpExtract = Join-Path $env:TEMP "jre-extract"
    if (Test-Path $tmpExtract) { Remove-Item -Recurse -Force $tmpExtract }
    Expand-Archive -LiteralPath $tmpZip -DestinationPath $tmpExtract -Force
    Write-Host "Extracted JRE"
    
    # Move to final location
    New-Item -ItemType Directory -Path $jreDir -Force | Out-Null
    $extracted = Get-ChildItem $tmpExtract
    if ($extracted.Count -eq 1 -and $extracted[0].PSIsContainer) {
        Get-ChildItem $extracted[0].FullName | Move-Item -Destination $jreDir -Force
    } else {
        Get-ChildItem $tmpExtract | Move-Item -Destination $jreDir -Force
    }
    Write-Host "JRE installed to $jreDir"
    
    # Verify
    $javaExe = Join-Path $jreDir "bin\java.exe"
    if (Test-Path $javaExe) {
        Write-Host "Java executable found: $javaExe"
        & $javaExe -version
    }
    
    # Cleanup
    Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse $tmpExtract -Force -ErrorAction SilentlyContinue
    
} catch {
    Write-Host "Error: $_"
    exit 1
}

Write-Host "=== JRE Setup Complete ==="

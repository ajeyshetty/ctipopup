param(
    [switch]$AutoDownload,
    [string]$JreUrl,
    [string]$ISCCPath = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    [string]$Launch4jPath = "C:\Users\axshetty\Downloads\launch4j-3.50-win32\launch4j\launch4j.exe",
    [ValidateSet('x86','x64')][string]$Arch = 'x64'  # default to 64-bit
)

Set-StrictMode -Version Latest

function Write-Log { param($m) Write-Host "[build_installer] $m" }

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$installerDir = $scriptDir
$jreDir = Join-Path $installerDir "jre"

Write-Log "Starting build_installer.ps1 (Arch=$Arch)"
Write-Log "Using Launch4j at: $Launch4jPath"

# Download JRE if requested
if ($AutoDownload -or $JreUrl) {
    if (Test-Path $jreDir) {
        Write-Log "Removing existing jre folder at '$jreDir'"
        Remove-Item -Recurse -Force $jreDir -ErrorAction Stop
    }
    
    if (-not $JreUrl) {
        # Use direct GitHub releases URL for 64-bit JRE
        if ($Arch -eq 'x64') {
            $JreUrl = "https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24.0.1%2B9/OpenJDK24U-jre_x64_windows_hotspot_24.0.1_9.zip"
        } else {
            $JreUrl = "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.25%2B9/OpenJDK11U-jre_x86-32_windows_hotspot_11.0.25_9.zip"
        }
        Write-Log "Using direct JRE URL for $Arch"
    }

    Write-Log "Downloading JRE from: $JreUrl"
    $tmpZip = Join-Path $env:TEMP "temurin-jre.zip"
    try {
        Invoke-WebRequest -Uri $JreUrl -OutFile $tmpZip -UseBasicParsing -ErrorAction Stop
    } catch {
        Write-Log "Failed to download JRE: $_"
        exit 1
    }

    $tmpExtract = Join-Path $env:TEMP "temurin-jre-extract"
    if (Test-Path $tmpExtract) { Remove-Item -Recurse -Force $tmpExtract }
    New-Item -ItemType Directory -Path $tmpExtract | Out-Null

    Write-Log "Extracting JRE zip..."
    try {
        Expand-Archive -LiteralPath $tmpZip -DestinationPath $tmpExtract -Force
    } catch {
        Write-Log "Failed to extract JRE archive: $_"
        exit 1
    }

    # Move contents into installer-inno/jre (flatten if archive had a single top-level folder)
    New-Item -ItemType Directory -Path $jreDir | Out-Null
    $children = @(Get-ChildItem -Path $tmpExtract)
    if ($children.Count -eq 1 -and $children[0].PSIsContainer) {
        Write-Log "Archive contains single root folder; moving its contents into jre/"
        Get-ChildItem -Path $children[0].FullName -Force | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $jreDir -Force }
    } else {
        Write-Log "Moving extracted files into jre/"
        Get-ChildItem -Path $tmpExtract -Force | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $jreDir -Force }
    }

    Remove-Item -Recurse -Force $tmpExtract
    Remove-Item -Force $tmpZip
    Write-Log "JRE prepared at: $jreDir"
}

# Verify JRE bitness matches requested Arch
if (Test-Path (Join-Path $jreDir 'bin\java.exe')) {
    $javaExe = (Join-Path $jreDir 'bin\java.exe')
    try {
        $out = & "$javaExe" -version 2>&1
        $joined = $out -join " "
        if ($joined -match '64[- ]?Bit' -or $joined -match 'x86_64' -or $joined -match 'amd64') { $jb = 'x64' }
        elseif ($joined -match '32[- ]?Bit' -or $joined -match 'x86' -or $joined -match 'i386') { $jb = 'x86' }
        else { $jb = $null }
        
        if ($jb) {
            Write-Log "Bundled java.exe reports: $jb"
            if ($jb -ne $Arch) {
                Write-Log "ERROR: bundled JRE architecture ($jb) does not match requested Arch ($Arch)."
                exit 2
            }
        } else {
            Write-Log "Warning: unable to determine bundled java.exe bitness."
        }
    } catch {
        Write-Log "Warning: could not test bundled java.exe"
    }
} else {
    Write-Log "Warning: bundled java.exe not found at expected location: $jreDir\bin\java.exe"
}

# Copy required JARs into installer directory
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path

# Copy ctippopup.jar
$ctipJarDst = Join-Path $installerDir 'ctippopup.jar'

# Prefer the freshly built production JAR in dist if available
$distJar = Join-Path $repoRoot 'dist\cti-popup-jtapi.jar'
if (Test-Path $distJar) {
    Copy-Item -Path $distJar -Destination $ctipJarDst -Force
    Write-Log "Copied latest distribution JAR from dist as ctippopup.jar"
} else {
    # Fall back to any ctippopup.jar at repo root
    $ctipJarSrc = Join-Path $repoRoot 'ctippopup.jar'
    if (Test-Path $ctipJarSrc) {
        Copy-Item -Path $ctipJarSrc -Destination $ctipJarDst -Force
        Write-Log "Copied existing ctippopup.jar from repo root"
    } else {
        Write-Log "Warning: ctippopup.jar not found in dist or repo root; installer will include whatever is present in installer-inno folder"
    }
}

# Copy jtapi.jar
$jtapiJarSrc = Join-Path $repoRoot 'lib\jtapi.jar'
$jtapiJarDst = Join-Path $installerDir 'jtapi.jar'
if (Test-Path $jtapiJarSrc) {
    Copy-Item -Path $jtapiJarSrc -Destination $jtapiJarDst -Force
    Write-Log "Copied jtapi.jar"
} else {
    Write-Log "Warning: jtapi.jar not found at $jtapiJarSrc"
}

# Run Launch4j OR create alternative launcher for true 64-bit support
$launch4jConfig = Join-Path $installerDir 'launch4j-config.xml'
$launcherExe = Join-Path $installerDir 'CTIPopup.exe'

if ($Arch -eq 'x64') {
    Write-Log "Creating 64-bit batch launcher instead of Launch4j (due to Launch4j 32-bit limitations)"
    
    # Create a batch file launcher for true 64-bit support
    $batchContent = @'
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
'@

    # Write the batch file
    $batchFile = Join-Path $installerDir 'CTIPopup.bat'
    Set-Content -Path $batchFile -Value $batchContent -Encoding ASCII
    Write-Log "Created batch launcher: $batchFile"
    
    # Create a VBScript launcher that runs the batch file silently (no command prompt window)
    $vbsContent = @'
Dim objShell, objFSO, scriptDir, batPath
Set objShell = CreateObject("Wscript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")
scriptDir = objFSO.GetParentFolderName(WScript.ScriptFullName)
batPath = scriptDir & "\CTIPopup.bat"
objShell.Run """" & batPath & """", 0, False
'@
    $vbsFile = Join-Path $installerDir 'CTIPopup.vbs'
    Set-Content -Path $vbsFile -Value $vbsContent -Encoding ASCII
    Write-Log "Created silent VBS launcher: $vbsFile"
    
    # For now, also create the exe via Launch4j for compatibility, but the batch will be the main launcher
    if ((Test-Path $Launch4jPath) -and (Test-Path $launch4jConfig)) {
        Write-Log "Also running Launch4j for fallback compatibility"
        try {
            & "$Launch4jPath" "$launch4jConfig"
            if (Test-Path $launcherExe) {
                Write-Log "Launch4j produced: $launcherExe (32-bit compatibility)"
            }
        } catch {
            Write-Log "Launch4j failed: $_"
        }
    }
} else {
    # For x86, use Launch4j as normal
    if ((Test-Path $Launch4jPath) -and (Test-Path $launch4jConfig)) {
        Write-Log "Running Launch4j to build CTIPopup.exe"
        try {
            & "$Launch4jPath" "$launch4jConfig"
            $lrc = $LASTEXITCODE
            if ($lrc -eq 0) {
                if (Test-Path $launcherExe) {
                    Write-Log "Launch4j produced: $launcherExe"
                } else {
                    Write-Log "Launch4j completed but CTIPopup.exe not found"
                }
            } else {
                Write-Log "Launch4j returned exit code $lrc"
            }
        } catch {
            Write-Log "Failed to run Launch4j: $_"
        }
    } else {
        Write-Log "Launch4j or config not found. Launch4j: $Launch4jPath, Config: $launch4jConfig"
    }
}

# Run Inno Setup
if (-not (Test-Path $ISCCPath)) {
    Write-Log "Inno Setup compiler not found at '$ISCCPath'"
    exit 1
}

$innoScript = Join-Path $installerDir "ctippopup.iss"
if (Test-Path $innoScript) {
    Write-Log "Running Inno Setup compiler"
    Push-Location $installerDir
    try {
        & "$ISCCPath" "ctippopup.iss"
        $rc = $LASTEXITCODE
        if ($rc -eq 0) {
            Write-Log "Installer built successfully!"
        } else {
            Write-Log "Inno Setup returned exit code $rc"
            exit $rc
        }
    } finally { 
        Pop-Location 
    }
} else {
    Write-Log "Inno Setup script not found at $innoScript"
    exit 1
}

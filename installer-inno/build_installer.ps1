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
$ctipJarSrc = Join-Path $repoRoot 'ctippopup.jar'
$ctipJarDst = Join-Path $installerDir 'ctippopup.jar'
if (Test-Path $ctipJarSrc) {
    Copy-Item -Path $ctipJarSrc -Destination $ctipJarDst -Force
    Write-Log "Copied ctippopup.jar"
} else {
    Write-Log "Warning: ctippopup.jar not found at $ctipJarSrc"
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

# Copy user-provided icon.ico if present in repo root or installer dir
$iconCandidates = @(
    (Join-Path $repoRoot 'icon.ico'),
    (Join-Path $installerDir 'icon.ico')
)
$copiedIcon = $false
foreach ($ic in $iconCandidates) {
    if (Test-Path $ic) {
        try {
            Copy-Item -Path $ic -Destination (Join-Path $installerDir 'icon.ico') -Force
            Write-Log "Copied icon.ico from $ic to installer dir"
            $copiedIcon = $true
        } catch {
            Write-Log "Warning: failed to copy icon.ico from $ic : $_"
        }
        break
    }
}
if (-not $copiedIcon) { Write-Log "No user-provided icon.ico found in repo root or installer dir" }

# Convert icon.png (if present) to an .ico file for the installer and shortcuts
function Write-PngAsIco {
    param(
        [string]$PngPath,
        [string]$IcoPath
    )

    if (-not (Test-Path $PngPath)) { throw "PNG not found: $PngPath" }

    Write-Log "Converting PNG to ICO: $PngPath -> $IcoPath"
    $pngBytes = [System.IO.File]::ReadAllBytes($PngPath)

    $fs = New-Object System.IO.FileStream($IcoPath, [System.IO.FileMode]::Create)
    $bw = New-Object System.IO.BinaryWriter($fs)

    try {
        # ICONDIR: Reserved (WORD 0), Type (WORD 1), Count (WORD 1)
        $bw.Write([uint16]0)
        $bw.Write([uint16]1)
        $bw.Write([uint16]1)

        # ICONDIRENTRY (16 bytes)
        # Width (BYTE), Height (BYTE) -- 0 means 256
        $bw.Write([byte]0)  # width = 256
        $bw.Write([byte]0)  # height = 256
        $bw.Write([byte]0)  # color count
        $bw.Write([byte]0)  # reserved
        $bw.Write([uint16]0) # planes (0 for PNG)
        $bw.Write([uint16]0) # bitcount (0 for PNG)
        $bw.Write([uint32]($pngBytes.Length)) # bytes in resource

        # The image data starts immediately after the headers (6 + 16 = 22)
        $imageOffset = 6 + 16
        $bw.Write([uint32]$imageOffset)

        # Write PNG data
        $bw.Write($pngBytes)
    } finally {
        $bw.Close()
        $fs.Close()
    }
}

# Look for icon.png in the repo root or installer dir
$pngCandidates = @(
    (Join-Path $repoRoot 'icon.png'),
    (Join-Path $installerDir 'icon.png')
)
$icoPath = Join-Path $installerDir 'ctippopup.ico'
foreach ($p in $pngCandidates) {
    if (Test-Path $p) {
        try {
            Write-PngAsIco -PngPath $p -IcoPath $icoPath
            Write-Log "ICON created at: $icoPath"
                    # If ICO is too large for embedding in setup, create a smaller setup ICO
                    $maxSetupIconBytes = 60000
                    try {
                        $icoSize = (Get-Item $icoPath).Length
                        if ($icoSize -gt $maxSetupIconBytes) {
                            Write-Log "Generated ICO is $icoSize bytes (too large for setup). Creating smaller setup ICO."
                            # Resize original PNG to 64x64 and write a small ICO
                            function Write-ResizedPngAsIco {
                                param($PngPath, $OutIcoPath, $size)
                                Add-Type -AssemblyName System.Drawing
                                $bmp = [System.Drawing.Bitmap]::FromFile($PngPath)
                                $small = New-Object System.Drawing.Bitmap $size, $size
                                $g = [System.Drawing.Graphics]::FromImage($small)
                                $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                                $g.DrawImage($bmp, 0, 0, $size, $size)
                                $g.Dispose()
                                $ms = New-Object System.IO.MemoryStream
                                $small.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
                                $pngBytes = $ms.ToArray()
                                $ms.Close()
                                $bmp.Dispose()
                                $small.Dispose()

                                $fs = New-Object System.IO.FileStream($OutIcoPath, [System.IO.FileMode]::Create)
                                $bw = New-Object System.IO.BinaryWriter($fs)
                                try {
                                    $bw.Write([uint16]0)
                                    $bw.Write([uint16]1)
                                    $bw.Write([uint16]1)
                                    $bw.Write([byte]0)
                                    $bw.Write([byte]0)
                                    $bw.Write([byte]0)
                                    $bw.Write([byte]0)
                                    $bw.Write([uint16]0)
                                    $bw.Write([uint16]0)
                                    $bw.Write([uint32]($pngBytes.Length))
                                    $imageOffset = 6 + 16
                                    $bw.Write([uint32]$imageOffset)
                                    $bw.Write($pngBytes)
                                } finally {
                                    $bw.Close()
                                    $fs.Close()
                                }
                            }

                            $setupIco = Join-Path $installerDir 'ctippopup_setup.ico'
                            Write-ResizedPngAsIco -PngPath $p -OutIcoPath $setupIco -size 64
                            Write-Log "Created smaller setup ICO at: $setupIco"
                        }
                    } catch {
                        Write-Log "Warning checking/creating small ICO: $_"
                    }
        } catch {
            Write-Log "Failed to create ICO: $_"
        }
        break
    }
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

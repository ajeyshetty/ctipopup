Building the single EXE installer (bundled JRE)
=============================================

This folder contains an Inno Setup script (`ctippopup.iss`) and a PowerShell helper
script (`build_installer.ps1`) to produce a single EXE installer that bundles the
application and an optional private JRE. Bundling a private JRE means target PCs
don't need a system Java installation.

Prerequisites
- Inno Setup 6 (ISCC.exe) installed. Default expected path: `C:\Program Files (x86)\Inno Setup 6\ISCC.exe`.
- Optionally a Launch4j-built `CTIPopup.exe` placed into this folder (the Inno script copies it).

Quick steps

1. To download a Temurin (Adoptium) JRE automatically and build the installer (defaults to 32-bit/x86 thick client):

```powershell
.
.\build_installer.ps1 -AutoDownload
```

To explicitly request 64-bit JRE instead (not typical for thick-client compatibility):

```powershell
.
.\build_installer.ps1 -AutoDownload -Arch x64
```

2. If you already have a JRE folder prepared at `installer-inno\jre`, run:

```powershell
.
.\build_installer.ps1
```

3. To use a custom ISCC path:

```powershell
.
.\build_installer.ps1 -ISCCPath 'C:\\path\\to\\ISCC.exe'
```

Notes
- The script downloads a Temurin 11 JRE by default. Modify the script or pass a direct
  `-JreUrl` if you need a different JRE version.
- The Inno script will include the entire `jre` folder if present. The packaged EXE
  will be self-contained and should run on machines without a system Java installation.
- Company security policies may still flag bundled JREs; consult your security team if
  required.

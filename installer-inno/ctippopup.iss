[Setup]
AppName=LKQ CTI Popup
AppVersion=1.0
DefaultDirName={autopf}\LKQ\CTI Popup
DefaultGroupName=LKQ CTI Popup
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=LKQ_CTIpopup_Installer
Compression=lzma/fast
SolidCompression=no
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
MinVersion=6.1sp1
DisableReadyPage=yes
ShowLanguageDialog=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop icon"; GroupDescription: "Additional icons:"

[Files]
; copy the batch launcher (primary 64-bit launcher)
Source: "{#SourcePath}\CTIPopup.bat"; DestDir: "{app}"; Flags: ignoreversion
; copy the VBS silent launcher (no command prompt)
Source: "{#SourcePath}\CTIPopup.vbs"; DestDir: "{app}"; Flags: ignoreversion
; copy the EXE wrapper (fallback compatibility)
Source: "{#SourcePath}\CTIPopup.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourcePath}\ctippopup.jar"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourcePath}\jtapi.jar"; DestDir: "{app}"; Flags: ignoreversion
; include jre folder if present (not mandatory)
Source: "{#SourcePath}\jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs createallsubdirs

[Dirs]
; make logs folder under LocalAppData and in app folder
Name: "{localappdata}\LKQ\CTIPopup\logs"; Flags: uninsalwaysuninstall
Name: "{app}\logs"; Flags: uninsalwaysuninstall

[Icons]
Name: "{group}\LKQ CTI Popup"; Filename: "{app}\CTIPopup.vbs"; WorkingDir: "{app}"
Name: "{userdesktop}\LKQ CTI Popup"; Filename: "{app}\CTIPopup.vbs"; Tasks: desktopicon; WorkingDir: "{app}"

[Run]
Filename: "{app}\CTIPopup.vbs"; Description: "Launch LKQ CTI Popup"; Flags: nowait postinstall skipifsilent; WorkingDir: "{app}"

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;

function DirExists(const Dir: string): Boolean;
begin
  Result := DirExists(Dir);
end;

#define SourcePath "{src}"

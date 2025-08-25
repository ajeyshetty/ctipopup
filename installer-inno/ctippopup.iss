[Setup]
AppName=LKQ CTI Popup
AppVersion=1.0
DefaultDirName={pf32}\LKQ\CTI Popup
DefaultGroupName=LKQ CTI Popup
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=LKQ_CTIpopup_Installer
Compression=lzma
SolidCompression=yes

[Files]
; copy the EXE wrapper and jars
Source: "{#SourcePath}\CTIPopup.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourcePath}\ctippopup.jar"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourcePath}\jtapi.jar"; DestDir: "{app}"; Flags: ignoreversion
; include jre folder if present (not mandatory)
Source: "{#SourcePath}\jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs createallsubdirs; Check: DirExists(ExpandConstant('{#SourcePath}\\jre'))

[Dirs]
; make logs folder under LocalAppData
Name: "{localappdata}\LKQ\CTIPopup\logs"; Flags: uninsalwaysuninstall

[Icons]
Name: "{group}\LKQ CTI Popup"; Filename: "{app}\CTIPopup.exe"
Name: "{userdesktop}\LKQ CTI Popup"; Filename: "{app}\CTIPopup.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\CTIPopup.exe"; Description: "Launch LKQ CTI Popup"; Flags: nowait postinstall skipifsilent

[Code]
var
  SourcePath: string;

function InitializeSetup(): Boolean;
begin
  SourcePath := ExpandConstant('{src}');
  Result := True;
end

#define SourcePath "{src}"

; Inno Setup 6.3+ script for the ShimejiSRC installer. Build with installer\build-installer.bat
; (it assembles a clean bundle first), or by hand:
;   ISCC.exe /DAppVersion=1.10 /DSourceDir=..\dist\Shimeji installer\ShimejiSRC.iss

#ifndef AppVersion
  #define AppVersion "1.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\Shimeji"
#endif

[Setup]
AppId={{1F6928A0-56AC-49AF-9D6D-A8CDC0A17D6E}
AppName=ShimejiSRC
AppVersion={#AppVersion}
AppPublisher=Ko (kobitz)
AppPublisherURL=https://github.com/kobitz/ShimejiSRC
AppSupportURL=https://github.com/kobitz/ShimejiSRC/issues
; Per-user by default (no admin prompt). The app writes settings.properties, memory.json and logs
; next to itself, so it must NOT live under C:\Program Files. "Install for all users" is offered
; by the dialog but then needs a writable folder of your choosing.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={autopf}\ShimejiSRC
DisableProgramGroupPage=yes
DefaultGroupName=ShimejiSRC
OutputBaseFilename=ShimejiSRC-Setup-{#AppVersion}
OutputDir=..\dist
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
LicenseFile=..\LICENSE
SetupIconFile={#SourceDir}\img\icon.ico
UninstallDisplayIcon={app}\Shimeji.exe
UninstallDisplayName=ShimejiSRC
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "startupicon"; Description: "Start ShimejiSRC when I sign in to Windows"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
; Everything except settings.properties: reinstalling/upgrading must never clobber the user's settings.
Source: "{#SourceDir}\*"; DestDir: "{app}"; Excludes: "settings.properties"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "{#SourceDir}\settings.properties"; DestDir: "{app}"; Flags: onlyifdoesntexist uninsneveruninstall skipifsourcedoesntexist

[Icons]
; WorkingDir matters: the app resolves img/, conf/ and settings relative to its working directory.
Name: "{autoprograms}\ShimejiSRC"; Filename: "{app}\Shimeji.exe"; WorkingDir: "{app}"
Name: "{autoprograms}\ShimejiSRC (Administrator - hardware sensors)"; Filename: "{app}\Admin.bat"; WorkingDir: "{app}"; IconFilename: "{app}\Shimeji.exe"
Name: "{autoprograms}\Set up AI features (Ollama, voice)"; Filename: "{app}\Setup.bat"; WorkingDir: "{app}"; IconFilename: "{app}\Shimeji.exe"
Name: "{autoprograms}\Uninstall ShimejiSRC"; Filename: "{uninstallexe}"
Name: "{autodesktop}\ShimejiSRC"; Filename: "{app}\Shimeji.exe"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{userstartup}\ShimejiSRC"; Filename: "{app}\Shimeji.exe"; WorkingDir: "{app}"; Tasks: startupicon

[Run]
Filename: "{app}\Shimeji.exe"; Description: "Launch ShimejiSRC"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent
Filename: "{app}\Setup.bat"; Description: "Set up AI features now (downloads Ollama + a ~4 GB model; optional, needs internet)"; WorkingDir: "{app}"; Flags: shellexec postinstall skipifsilent unchecked skipifdoesntexist

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM Shimeji.exe"; Flags: runhidden; RunOnceId: "KillShimeji"

[Code]
// Settings, memories and chat history are the user's data: keep them unless asked.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    if (not UninstallSilent) and DirExists(ExpandConstant('{app}')) then
      if MsgBox('Also delete your settings, mascot memories and chat history?' + #13#10 +
                'Choose No to keep them for a future install.', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(ExpandConstant('{app}'), True, True, True);
  end;
end;

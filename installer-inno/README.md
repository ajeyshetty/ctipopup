How to build the single EXE installer (Inno Setup)

1) Ensure you have Inno Setup installed: https://jrsoftware.org/isinfo.php
2) Prepare the installer-inno folder contents:
   - CTIPopup.exe (wrapper created by Launch4j)
   - ctippopup.jar
   - jtapi.jar
   - (optional) jre\  <-- put a JRE 11 here to bundle the runtime (recommended). The folder should contain bin\java.exe
3) Open `ctippopup.iss` in Inno Setup, point the SourcePath (if needed) to this folder, and click Compile.

Command-line build (if ISCC is on PATH):
   iscc ctippopup.iss

Notes:
- The installer will copy files into Program Files and create a logs folder in %LOCALAPPDATA%\LKQ\CTIPopup\logs to capture vendor logs.
- If you want me to download a Temurin JRE 11 and bundle it, tell me and I'll add it to this folder and optionally compile the installer for you.

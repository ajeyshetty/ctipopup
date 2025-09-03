JTAPI Caller Info

This small Java project demonstrates a JTAPI-based listener that prints the calling number when a phone event arrives.

Where to put jtapi.jar
- Place your `jtapi.jar` into the `lib` directory at the project root: `lib/jtapi.jar`.

Build & run (Windows PowerShell)

1. Compile

```powershell
javac -cp ".;lib\jtapi.jar" -d out src\JTAPICallerInfo.java
```

2. Run

```powershell
java -cp ".;out;lib\jtapi.jar" JTAPICallerInfo "cucm1;login=USER;passwd=PASS" "SEP000000000" 600
```

Notes
- Replace the provider string and address with values for your CUCM environment.
- The program needs network access to the CUCM JTAPI service and correct credentials.
 - For security, passwords are NOT persisted by the application. If "Remember Me" is enabled, only the username, CUCM host, and phone are stored locally; you must re-enter your password each run.

CMD
cd "c:\Users\axshetty\OneDrive - LKQ\Projects\CTIPopup-JTAPI\installer-inno"; powershell -NoProfile -ExecutionPolicy Bypass -File "c:\Users\axshetty\OneDrive - LKQ\Projects\CTIPopup-JTAPI\installer-inno\build_installer.ps1" -AutoDownload -Arch x64

cd "c:\Users\axshetty\OneDrive - LKQ\Projects\CTIPopup-JTAPI"; java -cp "bin;lib/jtapi.jar" JTAPIGui
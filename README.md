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

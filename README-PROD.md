# CTI Popup JTAPI

A production-ready Java application that provides Computer Telephony Integration (CTI) functionality using JTAPI (Java Telephony API). This application monitors phone calls and can automatically open URLs or perform screen pops based on call events.

## Features

- **Real-time Call Monitoring**: Monitors incoming and outgoing calls using JTAPI
- **Screen Pop Integration**: Automatically opens URLs when calls are received or connected
- **Flexible Triggering**: Supports different trigger points (RINGING, ALERTING, CONNECTED)
- **Address Filtering**: Can monitor specific phone extensions or all addresses
- **GUI Interface**: User-friendly Swing-based interface for configuration
- **Call Registry**: Tracks active calls with detailed information
- **Production Ready**: Clean, optimized code with proper error handling

## Prerequisites

- Java 8 or higher
- JTAPI-compatible PBX system (Cisco CUCM, etc.)
- JTAPI library (`jtapi.jar`) - place in `lib/` directory
- Network access to PBX system

## Project Structure

```
CTIPopup-JTAPI/
├── src/                    # Source code
│   ├── JTAPICallerInfo.java    # Core JTAPI listener
│   ├── JTAPIGui.java          # GUI interface
│   ├── CallRegistry.java      # Call tracking system
│   └── CallListPanel.java     # Call list display
├── lib/                    # Dependencies
│   └── jtapi.jar          # JTAPI library
├── bin/                    # Compiled classes (generated)
├── dist/                   # Production JAR (generated)
├── installer-inno/        # Windows installer files
├── build.bat              # Windows build script
├── build.ps1              # PowerShell build script
└── README.md              # This file
```

## Quick Start

### 1. Setup Dependencies

Place your JTAPI library in the correct location:
```bash
# Copy your jtapi.jar to the lib directory
cp /path/to/your/jtapi.jar lib/
```

### 2. Build the Application

**Using PowerShell (Recommended):**
```powershell
.\build.ps1
```

**Using Batch Script:**
```cmd
build.bat
```

**Manual Compilation:**
```bash
# Compile
javac -cp ".;lib/jtapi.jar" -d bin -sourcepath src src/*.java

# Create JAR
jar cfm dist/cti-popup-jtapi.jar manifest.txt -C bin .
```

### 3. Run the Application

**GUI Mode (Recommended):**
```bash
java -cp "bin;lib/jtapi.jar" JTAPIGui
```

**Command Line Mode:**
```bash
java -cp "bin;lib/jtapi.jar" JTAPICallerInfo "cucm1;login=USER;passwd=PASS" "SEP000000000" 600
```

**Production JAR:**
```bash
java -jar dist/cti-popup-jtapi.jar
```

## Configuration

### GUI Configuration

1. **CUCM Host**: IP address or hostname of your Cisco CUCM server
2. **Username/Password**: JTAPI user credentials
3. **Phone Extension**: The phone extension to monitor (or "ALL" for all extensions)
4. **URL Template**: URL to open for screen pops (e.g., `https://crm.example.com/search?phone={number}`)
5. **Trigger**: When to perform screen pop (RINGING, ALERTING, CONNECTED)
6. **Enable Screen Pop**: Toggle URL opening functionality

### URL Template Examples

- Basic search: `https://crm.example.com/search?phone={number}`
- Custom application: `myapp://call?caller={number}`
- Web service: `https://api.example.com/caller/{number}`

## Build Scripts

### PowerShell Build Script (`build.ps1`)

```powershell
# Full build
.\build.ps1

# Clean only
.\build.ps1 -Clean

# Compile only (no JAR)
.\build.ps1 -NoJar
```

### Batch Build Script (`build.bat`)

```cmd
# Full build
build.bat
```

## Production Deployment

### Windows Installer

The project includes Inno Setup configuration for creating Windows installers:

```cmd
cd installer-inno
powershell -NoProfile -ExecutionPolicy Bypass -File "build_installer.ps1" -AutoDownload -Arch x64
```

### JAR Deployment

1. Build the production JAR using the build scripts
2. Copy `dist/cti-popup-jtapi.jar` and `lib/jtapi.jar` to your deployment location
3. Run with: `java -jar cti-popup-jtapi.jar`

## Development

### Code Structure

- **JTAPICallerInfo**: Core JTAPI event listener and URL opening logic
- **JTAPIGui**: Swing-based user interface
- **CallRegistry**: Thread-safe call tracking and management
- **CallListPanel**: GUI component for displaying active calls

### Key Features

- **Thread Safety**: All shared data structures use proper synchronization
- **Error Handling**: Comprehensive exception handling and logging
- **Memory Management**: Uses WeakHashMap for automatic cleanup
- **Performance**: Efficient event processing with instanceof pattern

## Troubleshooting

### Common Issues

1. **JTAPI Connection Failed**
   - Verify CUCM host and credentials
   - Check network connectivity to CUCM
   - Ensure JTAPI service is enabled on CUCM

2. **Screen Pop Not Working**
   - Verify URL template format
   - Check if "Enable Screen Pop" is checked
   - Ensure default browser is configured

3. **Compilation Errors**
   - Verify jtapi.jar is in lib/ directory
   - Check Java version (8+ required)
   - Ensure all source files are present

### Logs

Application logs are written to:
- `call-events.log` - Application events and call information
- Console output - Real-time status messages

## License

This project is provided as-is for educational and production use.

## Support

For issues related to:
- JTAPI connectivity: Consult your PBX administrator
- Java/application issues: Check logs and verify configuration
- Build issues: Ensure all dependencies are properly installed

# Writer - Android Handwriting-to-Text App

## Environment

- **JAVA_HOME**: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` — must be set before running Gradle commands
- **ADB path**: `/c/Users/Durham/AppData/Local/Android/Sdk/platform-tools/adb.exe` — not on PATH, use full path

### Build

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

### Install to tablet

```bash
"/c/Users/Durham/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

# ToricSentry

Android 14 (API 34) target build is now supported via Gradle.

## Build

1. Ensure Android SDK Platform 34 and Build-Tools are installed.
2. Set SDK path in `local.properties`:

```properties
sdk.dir=C:\\Users\\<your-user>\\AppData\\Local\\Android\\Sdk
```

3. Run build:

```powershell
.\gradlew.bat build
```

## Project Structure

- `app/`: Android application module
- `app/src/main/java/com/yoshyhyrro/toricsentry/core/DetectorCore.kt`: detector logic
- `app/src/main/java/com/yoshyhyrro/toricsentry/core/QDeformedDetectorCore.kt`: Q-Deformed detector logic
- `app/src/main/java/com/yoshyhyrro/toricsentry/MainActivity.kt`: simple demo runner UI

## Demo Modes

- Standard BLE toric-code detector demo
- Q-Deformed toric-code detector demo

The root-level `Q-Deformed Toric Code Detector.kt` file remains as a standalone reference,
while the Android build uses the packaged implementation under `app/src/main/java/.../core/`.
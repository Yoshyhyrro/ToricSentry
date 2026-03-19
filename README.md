# ToricSentry

**ToricSentry** is an experimental Android application designed to detect hidden, malicious BLE (Bluetooth Low Energy) devices—such as ATM or POS skimmers—by modeling the physical environment through the lens of quantum error correction mapping (**Toric Code Model**).

Instead of relying solely on simple blocklists, ToricSentry translates the spatial and temporal anomalies of background BLE signals into "errors" propagating on a 2D topological lattice. When suspicious signals behave erratically (e.g., MAC address spoofing, irregular RSSI clusters), they form logical error loops that the detector mathematically catches.

## Core Features

- **Real-Time BLE Scanning**: Actively monitors the surrounding 2.4 GHz spectrum using Android's native BLE APIs.
- **Intelligent Noise Filtering (`DeviceFilter`)**: Physical environments are extremely noisy. ToricSentry applies an initial gatekeeping layer using Bluetooth SIG Company Identifiers (Vendor IDs) to seamlessly filter out harmless backgrounds:
  - Ignores known consumer electronics (Apple, Microsoft, Google, Sony PS4/PS5, Bose).
  - Skips non-BLE frequencies naturally (NFC / 13.56 MHz, standard Classic Bluetooth).
- **Topological Anomaly Detection (`DetectorCore`)**:
  - **Standard Toric-Code Mode**: Projects detected unknown signals onto a lattice map, identifying non-trivial topological error chains.
  - **Q-Deformed Mode**: An advanced variant of the core logic providing alternative anomaly weighting.

## Project Structure

- `app/src/main/java/com/yoshyhyrro/toricsentry/sensor/BleScanner.kt`: Interface for the input source.
- `app/src/main/java/com/yoshyhyrro/toricsentry/sensor/RealBleScanner.kt`: Real-world hardware implementations to fetch surrounding environment data using `BluetoothLeScanner`.
- `app/src/main/java/com/yoshyhyrro/toricsentry/filter/DeviceFilter.kt`: The whitelist gatekeeper filtering out registered MAC/Vendor IDs.
- `app/src/main/java/com/yoshyhyrro/toricsentry/core/`: Contains the pure, mathematical Toric Code detection engines.
- `app/src/main/java/com/yoshyhyrro/toricsentry/MainActivity.kt`: Demo UI and Android 12+ Bluetooth runtime permission handling.

## Build Instructions (Android 14 / API 34+)

1. Ensure Android SDK Platform 34 and Build-Tools are installed.
2. Provide your SDK path in `local.properties`:
   ```properties
   sdk.dir=C:\\Users\\<your-user>\\AppData\\Local\\Android\\Sdk
   ```
3. Run the Gradle build task:
   ```powershell
   .\gradlew.bat clean build
   ```
4. Install to your debug device:
   ```powershell
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Demo Modes in UI

You can switch between different tracking operations in the Android UI:
- **▶ BLE Scan Start** : Launches the real hardware BLE scanner to monitor the live, physical radio space in real-time, logging results to Logcat.
- **Run Standard Toric Test** : Triggers the mock environmental testing on the Standard Detector Core.
- **Run Q-Deformed Toric Test** : Triggers the mock environmental testing using the Q-Deformed Core logic.

*Note: The root-level legacy `Q-Deformed Toric Code Detector.kt` file remains in the repo as a standalone algorithm reference.*
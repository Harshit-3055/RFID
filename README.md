## RFID — Android BLE UID Viewer

A minimal, single-screen Android app (Jetpack Compose) that scans for an ESP32-based RFID broadcaster, connects over Bluetooth Low Energy (BLE), subscribes to a characteristic that notifies RFID UIDs, and displays the latest UID as a prominent heading.

### Table of contents
- Overview
- Hardware and firmware (ESP32)
- Bluetooth Low Energy design
- Android app design
- Permissions (Android 6–14)
- Build and run
- Usage
- Troubleshooting
- FAQ
- Privacy and security

---

## Overview
- **Goal**: Show the latest RFID card UID read by an ESP32 + MFRC522 on an Android phone.
- **Transport**: BLE GATT with notifications.
- **UX**: One button to Scan & Connect; UID appears as the heading, with a simple descriptive paragraph. A small status chip shows scan/connection state.

---

## Hardware and firmware (ESP32)

Your ESP32 advertises a custom service and pushes the UID as notifications when a new card is read.

- Board: ESP32
- RFID reader: MFRC522 (RC522)
- Pins (from your sketch):
  - SCK=18, MISO=19, MOSI=23, SS=21, RST=22
- BLE stack: `NimBLE-Arduino`
- BLE identifiers:
  - Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
  - UID Characteristic (READ | NOTIFY): `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
  - Status Characteristic (READ): `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

ESP32 behavior (summary):
- Initializes MFRC522; polls every ~50 ms.
- On new card detection, converts the UID to hex (e.g., `AA BB CC DD`) and updates the UID characteristic; if connected, it sends a GATT notification.
- Starts advertising the custom service; auto restarts advertising on disconnect.

Tip: In many setups, the device advertises with name `ESP32-RFID`. Some phones may only reveal the name in scan response records; this app handles both name and service UUID discovery paths.

---

## Bluetooth Low Energy design

Android app performs the following flow:
1. Requests runtime permissions required for BLE (varies by Android version).
2. Starts an unfiltered BLE scan (like nRF Connect) and filters results in the callback:
   - Match if the advertisement includes the custom service UUID, or
   - Match if the device name contains `ESP32-RFID` (case-insensitive).
3. On the first match, stops scanning and connects with GATT.
4. Discovers services and obtains the UID characteristic.
5. Subscribes to notifications by writing to the Client Characteristic Configuration Descriptor (CCCD, UUID `00002902-0000-1000-8000-00805f9b34fb`).
6. Reads the initial UID value once, then updates the UI on every notification.

Notification subscription details:
- `BluetoothGatt#setCharacteristicNotification(uidChar, true)` enables notification routing.
- The app writes `ENABLE_NOTIFICATION_VALUE` to the characteristic's CCCD.
- When a new UID arrives, `onCharacteristicChanged` decodes it as a string and displays it.

Scan strategy:
- Uses `SCAN_MODE_LOW_LATENCY` for quick discovery.
- Shows up to the first 10 nearby device names in status while scanning.
- 30-second timeout to stop scanning if nothing matches (tap Scan again to retry).

---

## Android app design

Tech stack:
- Kotlin, Jetpack Compose (Material 3)
- Single activity: `com.example.rfid.MainActivity`
- Minimal state: `status`, `uidText`, `isScanning`, `seenDevices`

UI structure:
- Gradient background.
- Centered Card with:
  - Status chip (Scanning / Connected / Not found / etc.)
  - Large heading for the UID value
  - Short description paragraph (placeholder lorem ipsum)
  - Full-width “Scan & Connect” button

---

## Permissions (Android 6–14)

The app requests only the minimum permissions for BLE and adapts by API level.

- Android 12+ (API 31+):
  - `BLUETOOTH_SCAN` (declared with `neverForLocation`)
  - `BLUETOOTH_CONNECT`
  - No location permission required for scanning.

- Android 6–11 (API 23–30):
  - `ACCESS_FINE_LOCATION` is required for BLE scanning by platform policy.

Manifest highlights (already added):
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- Fallbacks for pre-Android 12 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

Runtime permission prompts are triggered when the user taps “Scan & Connect”. Grant the prompts to proceed.

---

## Build and run

Prerequisites:
- Android Studio (Giraffe+ recommended)
- Android SDK 24+ (minSdk 24, targetSdk 36)

Steps:
1. Open the project folder `RFID/` in Android Studio.
2. Let Gradle sync finish.
3. Connect an Android device (Android 6+) with Bluetooth enabled.
4. Build and run the `app` configuration.

Notes:
- For physical devices on Android 12+, ensure you grant the BLE permissions when prompted.
- The app uses Jetpack Compose; no additional setup is required beyond a standard Android Studio configuration.

---

## Usage
1. Power your ESP32 + RC522 board; ensure it is advertising (your sketch starts advertising on boot).
2. Open the app.
3. Tap “Scan & Connect”. The status chip will show “Scanning…”.
4. When your device is found, the app will connect, subscribe, and the latest UID will appear as the large heading.
5. Present an RFID card to the RC522; the heading updates when a new UID is read.

---

## Troubleshooting

- Not found — Tap to try again
  - Make sure the ESP32 is powered and advertising.
  - Stand closer (1–3 meters) and keep the phone’s Bluetooth on.
  - Some devices only include the name in the scan response; the app handles batch results and will still connect.
  - Retry the scan (30s timeout between attempts).

- Connects but no UID appears
  - Confirm the ESP32 is reading the RC522 and printing UIDs to Serial.
  - Ensure the UID characteristic `6E400002-…` is actually updated by the firmware.
  - Verify notifications are enabled in firmware (this app enables CCCD on connect).

- Permissions denied
  - On Android 12+, both `Bluetooth Scan` and `Bluetooth Connect` must be granted.
  - On Android 6–11, Location permission must be granted for BLE scanning.

- Works in nRF Connect but not here
  - This app mirrors nRF Connect’s unfiltered scan strategy and connects on the first match by service UUID or name. If your ESP advertises neither, add the service UUID to advertising data or set the advertised name to `ESP32-RFID`.

---

## FAQ

Q: Can I change the advertised name?
- Yes, in the ESP32 sketch, change the name passed to `NimBLEDevice::init("ESP32-RFID")`.

Q: Can I change the service/characteristic UUIDs?
- Yes, but update both the ESP32 firmware and the constants in the Android app (`SERVICE_UUID` and `UID_CHAR_UUID`).

Q: Does the app support multiple ESPs?
- The app connects to the first matching device it sees. You can extend the UI to list devices and choose one if needed.

---

## Privacy and security
- This app only uses Bluetooth for scanning and connecting to your ESP32. It does not send data to a server.
- BLE broadcasts are observable by nearby devices. If privacy matters, consider randomizing your ESP32 address and using encrypted characteristics.

---

## Key files
- `app/src/main/AndroidManifest.xml`: BLE permissions and feature declarations.
- `app/src/main/java/com/example/rfid/MainActivity.kt`: Entire UI and BLE logic (scan, connect, subscribe, display).
- `gradle/libs.versions.toml`: Dependency versions.

---

## License
This project is provided as-is without warranty. Use at your own risk.



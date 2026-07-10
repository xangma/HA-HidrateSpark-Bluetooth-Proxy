# HidrateSpark Health Sync for Android

This standalone phone app connects directly to a HidrateSpark bottle over
Bluetooth Low Energy and writes each buffered sip to Health Connect. It does
not require Home Assistant, the Hidrate cloud, a server, or an Internet
connection.

## Requirements

- Android 9 (API 28) or newer
- Health Connect (built into Android 14+, separate Google app on Android 9–13)
- A Bluetooth Low Energy radio
- A bottle initialized once with the official HidrateSpark app
- For building: JDK 17+ and Android SDK 36

The bottle can serve only one Bluetooth client at a time. Fully close the
official Hidrate app and disable any Home Assistant HidrateSpark connection
while using this companion.

## Build and install

From this directory:

```shell
./gradlew testDebugUnitTest assembleDebug
./gradlew installDebug
```

The debug APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`.

Open **HidrateSpark Health Sync** and:

1. Tap **Scan for HidrateSpark bottle**. Move the bottle to wake it if needed.
2. Select the bottle and confirm its capacity in millilitres. This matters
   because sip frames report volume as a percentage of capacity.
3. Tap **Save, grant access, and sync** and allow Nearby Devices and
   write-only hydration access.

You can enter the bottle's Bluetooth MAC address manually if scanning does not
find it. After the first successful setup, WorkManager reconnects directly to
the saved address periodically; it does not perform background scans.

## Reliability model

- The phone performs the HydroSync handshake and supports both modern
  `USER_DATA` and legacy `DATA_POINT` bottle firmware paths.
- Each decoded sip is committed to a local SQLite journal before the app sends
  the `0x57` acknowledgement that removes it from the bottle's queue.
- Health Connect records have deterministic `clientRecordId` values derived
  from local event IDs, so retrying an interrupted write updates rather than
  duplicates the record.
- Journal rows are marked complete only after Health Connect accepts a full
  batch. The last 5,000 completed rows remain available for replay detection.
- Manual and background syncs share a process-wide lock, preventing two phone
  connections or cursor races.
- WorkManager requests a sync every 15 minutes, but Android may defer it for
  battery optimization. The bottle buffers sip events until the next
  successful connection.

The app currently configures one bottle at a time.

## Permissions and privacy

The app requests:

- Nearby Devices (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`) on Android 12+
- Location on Android 9–11, because those versions require it for BLE scanning
- Write-only Health Connect hydration access

Bluetooth results are never used for location. The app has no Internet
permission, analytics, advertising SDKs, account, access token, or third-party
service. App backups and device-transfer backups are disabled. It never reads
Health Connect data.

## Upgrading from the Home Assistant-based prototype

Version 0.2 ignores the old Home Assistant URL and token settings. Select the
bottle once in the new screen. Existing Health Connect records remain in place;
new direct-Bluetooth events use separate stable IDs.

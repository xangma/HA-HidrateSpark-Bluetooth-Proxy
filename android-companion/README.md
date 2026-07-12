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
3. Tap **Start live sync** and allow Nearby Devices and write-only hydration
   access. Keep the displayed notification while you want immediate updates.

The Bluetooth address field is only a last-observed diagnostic value. The app
uses the selected advertised bottle name as identity, scans for that name
before every new GATT connection, and stores the newly discovered private
address only as a cache. This is required because the bottle rotates addresses.

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
- Manual, live, and recovery syncs share a process-wide lock, preventing
  concurrent drain transactions or cursor races.
- **Start live sync** runs a `connectedDevice` foreground service. It keeps one
  GATT notification subscription open, drains only when the pending-count
  notification increases, and ignores unchanged heartbeats and acknowledgement
  count decreases.
- WorkManager requests a recovery sync every 15 minutes, but Android may defer
  it for battery optimization. It scans for the current address before it
  reconnects, so the bottle buffers events until the next successful recovery.

The app currently configures one bottle at a time.

## Test whether advertisements signal new data

The setup screen includes a 90-second advertisement diagnostic. Select the
bottle, start the test, and then:

1. Leave the bottle still for 20 seconds.
2. Take a sip and immediately tap **Mark sip**.
3. Wait 20 seconds, move the bottle without drinking, and tap
   **Mark movement**.
4. When the test finishes, tap **Copy advertisement report**.

The report compares raw BLE advertisement packets and timing around both
markers. This test does not connect to the bottle or acknowledge any queued
sips.

The setup screen also includes a five-minute `FIRST_MATCH / MATCH_LOST` test.
It uses a low-power, name-filtered BLE scan to check whether Android's presence
callbacks can provide a dependable background wake-up signal.

## Device test results (12 July 2026)

These results were measured with one HidrateSpark bottle named `h2o00008928`
and a Samsung SM-F966B running Android 16 (API 36). The official Hidrate app
was closed. They characterize this bottle and phone combination rather than
every HidrateSpark model or Android Bluetooth implementation.

### Advertisement contents and cadence

Two 90-second captures produced these results:

| Capture | Advertisements | Average interval | Maximum interval | Unique raw payloads |
| --- | ---: | ---: | ---: | ---: |
| Initial capture while the bottle was being held | 185 | 0.474 s | 5.040 s | 1 |
| Controlled capture, starting upright and still | 144 | 0.617 s | 7.031 s | 1 |

The controlled capture compared ten-second windows around the user-marked
events:

| Window | Packets | Average interval | Maximum interval |
| --- | ---: | ---: | ---: |
| Before sip, bottle stationary | 6 | 1.406 s | 2.011 s |
| After sip | 10 | 0.562 s | 1.012 s |
| Before later movement | 25 | 0.391 s | 1.271 s |
| After movement without drinking | 27 | 0.352 s | 0.757 s |

The raw advertisement payload did not change after either the sip or movement.
Both actions increased advertisement frequency, and the higher frequency
continued for at least tens of seconds. Cadence therefore indicates recent
motion, not specifically a new sip, and cannot safely be used as a sync trigger
by itself.

### Android presence callbacks

The name-filtered low-power scan requested
`CALLBACK_TYPE_FIRST_MATCH | CALLBACK_TYPE_MATCH_LOST` for 300 seconds while
the bottle remained upright, still, and nearby. Android registered the scanner
successfully, but delivered zero `FIRST_MATCH` and zero `MATCH_LOST` callbacks.
An ordinary scan immediately afterward rediscovered the bottle at -54 dBm.

This shows that these `ScanCallback` presence events are not dependable on the
tested Samsung/Android stack. It does not test or rule out every Android device
or the separate Companion Device Manager presence API.

### GATT notification probe

A two-minute GATT probe rediscovered `h2o00008928` at the current private
address `C5:C0:27:8C:85:D3` (-72 dBm), completed the normal connection
handshake, and subscribed to the legacy `DATA_POINT` characteristic. The
modern `USER_DATA` characteristic was not present on this bottle.

The probe deliberately did **not** send `0x57` (the drain/acknowledgement
command), so it left the bottle's queued records untouched. It saw no
subscription-baseline notification, then received 13 notifications after the
sip prompt:

| Observation | Result |
| --- | --- |
| First notification | 6.941 s; 6 records pending |
| Last unchanged value | 26.952 s; 6 records pending |
| Queue-state change after the sip | 28.581 s; 7 records pending |
| Subsequent notifications | 7 records pending through 116.951 s |

The bottle also repeated unchanged queue-state notifications roughly every ten
seconds. Each observed frame contained only the pending-record count followed
by zero bytes; it did not carry the sip's volume or timestamp. The queue count
therefore provides a push cue, while the normal drain transaction is still
needed to retrieve and acknowledge the actual sip record.

### Address rotation and design implications

The stable advertised name remained `h2o00008928`, but three different private
Bluetooth addresses were observed during the tests. A saved MAC address is
therefore not a durable bottle identity.

The observations support the following implementation choices:

- Rediscover the bottle by its stable advertised name or service before each
  GATT connection; do not rely on the previously saved address.
- Do not infer a sip from advertisement contents or timing.
- Do not make `FIRST_MATCH / MATCH_LOST` the only background sync trigger.
- While an active GATT subscription is maintained, treat an increase in the
  notified pending-record count as the near-real-time sync trigger. Debounce
  unchanged repeats and serialize the drain so its acknowledgement does not
  start another concurrent sync.
- Sync opportunistically when the app is opened, retain periodic WorkManager
  sync as a delayed fallback. An active GATT subscription only provides push
  while Android is allowed to keep that connection alive; use an explicit
  foreground/connected-device mode when temporary near-real-time syncing is
  required.

## Permissions and privacy

The app requests:

- Nearby Devices (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`) on Android 12+
- Location on Android 9–11, because those versions require it for BLE scanning
- Connected-device foreground-service permission while live sync is enabled
- Write-only Health Connect hydration access

Bluetooth results are never used for location. The app has no Internet
permission, analytics, advertising SDKs, account, access token, or third-party
service. App backups and device-transfer backups are disabled. It never reads
Health Connect data.

## Upgrading from the Home Assistant-based prototype

Version 0.2 ignores the old Home Assistant URL and token settings. Select the
bottle once in the new screen. Existing Health Connect records remain in place;
new direct-Bluetooth events use separate stable IDs.

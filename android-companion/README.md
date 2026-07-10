# HidrateSpark Health Sync for Android

This companion transfers individual HidrateSpark sip events from Home
Assistant to Health Connect. It is a phone app: Health Connect lives on the
Android phone, while the bottle can continue connecting to Home Assistant
through a local Bluetooth adapter or ESPHome Bluetooth proxy.

## Requirements

- Android 9 (API 28) or newer
- Health Connect (built into Android 14+, separate Google app on Android 9–13)
- This fork of the Home Assistant integration, restarted after installation
- A Home Assistant long-lived access token
- A trusted HTTPS URL through which the phone can reach Home Assistant
- For building: JDK 17+ and Android SDK 36

Self-signed or private-CA certificates only work if the Android device trusts
that CA. Plain HTTP is deliberately rejected because it would expose the Home
Assistant bearer token.

## Build and install

From this directory:

```shell
./gradlew testDebugUnitTest assembleDebug
./gradlew installDebug
```

The debug APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`.

Open **HidrateSpark Health Sync**, enter the base Home Assistant URL and a
long-lived access token, then choose **Save, grant access, and sync**. After
permission is granted, the app performs an immediate sync and schedules unique
periodic work with a 15-minute interval. Android may defer background work for
battery optimization or network availability.

## Reliability model

- Home Assistant retains the last 5,000 accepted sips per bottle.
- The app stores a cursor for each HA server, config entry, and persistent
  journal identity, and downloads pages of 200.
- A cursor is committed only after Health Connect accepts the complete page.
- Every Health Connect record has a deterministic `clientRecordId`, so retrying
  an interrupted page updates the same record instead of duplicating it.
- Cursor writes are monotonic, protecting against overlapping manual and
  background sync runs.
- If a phone has been offline beyond the retained journal, the app imports all
  records still available and reports the history gap.

When an existing v0.2 installation first upgrades, only its persisted recent
sip deduplication window (up to 50 drinks) is available to seed historical
sync. All newly accepted sips use the larger journal.

## Privacy and security

The app requests only `WRITE_HYDRATION`; it does not read Health Connect data.
The HA token is encrypted with an AES-GCM key held in Android Keystore. App
backups and device-transfer backups are disabled. Network redirects and clear
text traffic are disabled. There are no analytics, advertising SDKs, or third-
party services.

The authenticated integration endpoints are:

- `GET /api/hidratespark/bottles`
- `GET /api/hidratespark/bottles/{entry_id}/sips?after={cursor}&limit={limit}`

Both require the normal Home Assistant `Authorization: Bearer …` header.

# HidrateSpark — Home Assistant integration

[![HACS](https://img.shields.io/badge/HACS-Custom-41BDF5?style=flat-square)](https://github.com/hacs/integration)
[![Release](https://img.shields.io/github/v/release/xangma/HA-HidrateSpark-Bluetooth-Proxy?style=flat-square)](https://github.com/xangma/HA-HidrateSpark-Bluetooth-Proxy/releases)
[![Release date](https://img.shields.io/github/release-date/xangma/HA-HidrateSpark-Bluetooth-Proxy?style=flat-square)](https://github.com/xangma/HA-HidrateSpark-Bluetooth-Proxy/releases)
[![Downloads](https://img.shields.io/github/downloads/xangma/HA-HidrateSpark-Bluetooth-Proxy/total?style=flat-square)](https://github.com/xangma/HA-HidrateSpark-Bluetooth-Proxy/releases)
[![License](https://img.shields.io/github/license/xangma/HA-HidrateSpark-Bluetooth-Proxy?style=flat-square)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/xangma/HA-HidrateSpark-Bluetooth-Proxy?style=flat-square)](https://github.com/xangma/HA-HidrateSpark-Bluetooth-Proxy/commits)
[![Stars](https://img.shields.io/github/stars/xangma/HA-HidrateSpark-Bluetooth-Proxy?style=flat-square)](https://github.com/xangma/HA-HidrateSpark-Bluetooth-Proxy/stargazers)

[![Open in HACS](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=xangma&repository=HA-HidrateSpark-Bluetooth-Proxy&category=integration)

A native Home Assistant integration for HidrateSpark smart water bottles.
Connects via Bluetooth — local adapter **or an ESPHome Bluetooth proxy** — and
exposes the bottle as a Home Assistant device. No MQTT broker, no Docker
container, no Linux box near the bottle.

This fork also includes an Android companion that synchronizes individual
drinks to Health Connect without using the Hidrate cloud.

This is a port of [HidrateSpark-MQTT-bridge][bridge] re-architected onto Home
Assistant's own Bluetooth stack.

[bridge]: https://github.com/loryanstrant/HidrateSpark-MQTT-bridge

## Why this exists

The MQTT bridge needs a Linux host with a working Bluetooth adapter near the
bottle. That's fine for a Raspberry Pi on the kitchen counter, less fine if
your Home Assistant install is in a closet on the other side of the house.

This integration uses HA's `bluetooth` integration for transport, which means
**any ESPHome Bluetooth proxy** in range of the bottle is enough — the proxy
relays GATT operations back to HA, and this integration runs the same
HydroSync handshake and frame parser end-to-end. The same code path also
works with a directly-attached USB or onboard adapter.

## Features

- 🔋 Battery percentage
- 💧 Per-sip events with timestamps and dedup (within ±2 s / same volume)
- 📊 Daily total (rolls at local midnight) and lifetime total
- 🚰 Auto-refill detection (cap-open/close + weight delta ≥ 25 mL)
- ⚖️ Live fill level from the on-bottle weight sensor (auto-calibrated)
- 🛟 Sip-exceeds-fill fallback when no weight anchor exists yet
- 🔁 State persists across HA restarts via the Store API
- 📥 Buffered sips replay on reconnect with their original timestamps
- ❤️ Authenticated, retry-safe Health Connect sync through the Android companion

**NOTE:** This has only been tested on a HidrateSpark PRO (v1) 21oz / 621ml with chug lid.

## Requirements

- Home Assistant 2024.4 or later
- The `bluetooth` integration set up (it's the default on HAOS)
- Either a local Bluetooth adapter on the HA host **or** an ESPHome
  Bluetooth proxy within range of the bottle
- The bottle paired once with the official HidrateSpark phone app — this puts
  it into "remembered" mode and registers its MAC

## Install

### HACS (recommended)

1. HACS → ⋮ → **Custom repositories**
2. Add `https://github.com/xangma/HA-HidrateSpark-Bluetooth-Proxy` as an
   **Integration**
3. Install **HidrateSpark Bluetooth Proxy**, restart HA
4. **Settings → Devices & Services → Add Integration → HidrateSpark**

### Manual

Copy `custom_components/hidratespark_bluetooth_proxy/` into your HA
`config/custom_components/` folder, restart HA, then add the integration from
**Devices & Services**.

## Setup

If your bottle is already advertising in range of HA (or a proxy), it appears
as a Bluetooth discovery and you only need to confirm the capacity. Otherwise
**+ Add Integration → HidrateSpark** lets you pick the bottle from a list of
discovered devices, or type its MAC address directly.

You can change the bottle's capacity later via the integration's **Configure**
button.

## Health Connect sync

Health Connect can only be written by an Android app, so the integration and
the phone companion work together:

1. Install this fork in Home Assistant and restart HA.
2. In your HA profile, create a **long-lived access token**.
3. Build and install the Android companion:

   ```shell
   cd android-companion
   ./gradlew installDebug
   ```

4. Open **HidrateSpark Health Sync**, enter your trusted HTTPS Home Assistant
   URL and token, then tap **Save, grant access, and sync**.

The companion needs Android 9 or newer. Health Connect is built into Android
14+; Android 9–13 users must install Google's Health Connect app. The HA URL
must use HTTPS with a certificate trusted by the phone.

Every accepted sip is placed in a durable, 5,000-event journal. The companion
pages through that journal with an authenticated cursor, writes one
`HydrationRecord` per sip, and only advances the cursor after Health Connect
accepts the page. Stable client record IDs make retries idempotent. WorkManager
runs the sync periodically with a 15-minute interval.

The access token is encrypted with Android Keystore and excluded from Android
backup. The companion requests write-only hydration access, reads no health
data, has no analytics, and sends data only between the configured HA server
and Health Connect. See [the companion documentation](android-companion/README.md)
for build details and limitations.

## Coexisting with the phone app

The bottle accepts only one BLE central at a time. If the official app is
actively connected, this integration won't be able to read the bottle until
the app disconnects. Recommended setups:

- **Best:** uninstall the phone app once you're set up
- **Acceptable:** force-quit it, or revoke its Bluetooth permission, when
  you're at home
- **Occasional firmware updates:** open the phone app briefly, then close it

## Entities

| Entity | Type |
|---|---|
| Battery | sensor (%, `battery`) |
| Water today | sensor (mL, `total_increasing`) |
| Water lifetime | sensor (mL, `total_increasing`) |
| Current fill | sensor (mL) |
| Current fill percent | sensor (%) |
| Last sip volume | sensor (mL) |
| Last sip time | sensor (timestamp) |
| Bottle weight (raw) | diagnostic, disabled by default |
| Connected | binary_sensor (connectivity) |

## What survives a disconnect

| Data | Buffered on bottle? | Recovered on reconnect? |
|---|---|---|
| Sips (volume + timestamp) | ✅ Yes | ✅ Replayed with original timestamps |
| Daily / lifetime totals | derived | ✅ Recomputed from replayed sips |
| Battery | ✅ | ✅ Re-read on connect |
| Current fill | ✅ | ✅ Snaps to true value within ~2 s |
| Cap open/close events | ❌ notify-only | ❌ Lost while away |
| Refill events while away | ❌ notify-only | ❌ Resulting fill level recovered, the event itself is not |

## Acknowledgements

- BLE handshake sequence and frame layout originally from
  [HydroSync](https://github.com/maxperron/HydroSync) (GPL-3.0).
- Cap-state and weight-sensor characteristics reverse-engineered against
  HidrateSpark Steel/PRO firmware 80.18 on the nRF52832, documented in the
  upstream MQTT bridge.

## License

MIT

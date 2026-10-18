# Architecture (planned)

## Stack

- Kotlin, Jetpack Compose, Material 3
- minSdk 29 (Android 10: `WifiNetworkSpecifier`, per-network socket factories), targetSdk current
- OkHttp for HTTP/1.1, with the socket factory of a chosen `android.net.Network`
- Protobuf: either a small hand-written encoder/decoder in `:protocol` (preferred, the subset is tiny), or Wire generating from `proto/sl_mini_subset.proto`. Don't add full gRPC libraries.
- No Google Play Services dependency, so the app runs on de-Googled phones and can go to F-Droid.

## Modules

```
:protocol   pure Kotlin/JVM
            ├─ Frames.kt        gRPC-web framing, trailer parsing, header status
            ├─ Wire.kt          varint / length-delimited encode + decode
            ├─ Requests.kt      builders: getConfig(), getDeviceInfo(), getStatus(), setupBypass(), setupWifi(name, pw)
            ├─ Responses.kt     parsers: WifiConfig subset, DeviceInfo, WifiGetStatus subset, Status
            └─ MiniClient.kt    suspend fun call(req): Result  (takes an HTTP transport interface)

:app        Android
            ├─ net/StarlinkNetworks.kt   find or request the network to talk over
            ├─ net/OkHttpTransport.kt    implements the :protocol transport on a bound Network
            ├─ state/MiniStateDetector.kt  probes → SetupPending | Configured | Bypass | Rebooting | NotFound
            ├─ data/Profile.kt           saved Wi-Fi name + password (Keystore-encrypted)
            └─ ui/…                      Compose screens
```

## Reaching the Mini from Android

The Mini's networks have no internet while the subscription is inactive (and during setup), so Android will not route ordinary app traffic there. The app must pick the network itself:

1. **Ethernet:** look through `ConnectivityManager.allNetworks` for `TRANSPORT_ETHERNET` whose link has an address in the router's subnet or a CGNAT address (bypass).
2. **Setup Wi-Fi:** if the phone is already on `STARLINK`, use that `TRANSPORT_WIFI` network. Otherwise offer to connect with `ConnectivityManager.requestNetwork(NetworkRequest with WifiNetworkSpecifier(ssid = "STARLINK"))`. Android shows its own confirmation dialog; the network stays app-local and doesn't replace the user's Wi-Fi.
3. Build the OkHttp client with `network.socketFactory` (and a DNS that only resolves literal IPs). Never use `bindProcessToNetwork` for the whole app.

Probe order: Ethernet first (survives the Wi-Fi switching off), then setup Wi-Fi.

## Main flow

```
Home ─ detect state ─┬─ SetupPending ─▶ choose: [Wi-Fi off] or [Secure Wi-Fi (profile)]
                     │                     └─ confirm screen ─▶ send wifi_setup ─▶ verify ─▶ result
                     │                          result, Wi-Fi off: "connect the Mini to your router's WAN port"
                     │                          result, Wi-Fi on:  password in large type + Wi-Fi QR code
                     ├─ Configured  ─▶ show SSIDs, auth, versions; explain: "To switch, factory reset the Mini"
                     ├─ Bypass      ─▶ show dish status; explain the same
                     ├─ Rebooting   ─▶ wait with progress (poll every 3 s, up to 3 min)
                     └─ NotFound    ─▶ guide: cable or join STARLINK Wi-Fi, check reset steps
```

Verification after a write:

- **Bypass:** expect the router to stop answering and, on Ethernet, a CGNAT address within about 60 s. On Wi-Fi the connection drops; say so before sending.
- **Secure Wi-Fi:** poll `wifi_get_config` until `setup_complete` is true and the BSS auth is no longer `auth_open`. The router restarts once; tolerate 1–2 minutes of no answer.

## Quick-switch extras (later milestones)

- One saved profile (name + password) so secure Wi-Fi setup is one tap after a reset.
- Quick Settings tile and home-screen shortcut that open straight into the pending-setup action.
- A "field card" screen showing the reset steps offline.

## Error handling

Map device answers to plain messages:

| Device answer | Message to the user |
|---|---|
| 9 `Wifi has already been set up` | "This Mini is already set up. Factory reset it first (reset icon on the back, hold until it clicks, ~3 s)." |
| 7 `Permission denied` | "The Mini refused this change. Only the official Starlink app can change a configured Mini." |
| timeout / no route | "Can't reach the Mini. Connect a cable or join the STARLINK Wi-Fi." |

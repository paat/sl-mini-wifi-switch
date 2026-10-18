# Architecture (planned)

## Stack

- Kotlin, Jetpack Compose, Material 3
- minSdk 29 (Android 10: `WifiNetworkSpecifier`, per-network socket factories), targetSdk current
- OkHttp for HTTP/1.1, with the socket factory of a chosen `android.net.Network`
- Protobuf: a small hand-written encoder/decoder in `:protocol`, using only the documented subset. No protobuf or gRPC libraries.
- No Google Play Services dependency, so the app runs on de-Googled phones and can go to F-Droid.

## Modules

```
:protocol   pure Kotlin/JVM
            ├─ Frames.kt        gRPC-web framing, trailer parsing, header status
            ├─ Wire.kt          varint / length-delimited encode + decode
            ├─ Requests.kt      builders: wifiGetConfig(), getDeviceInfo(), getStatus(), setupBypass(), setupWifi(name, password)
            ├─ Responses.kt     parsers: WifiConfig subset, DeviceInfo, WifiGetStatus subset, Status
            └─ MiniClient.kt    suspend fun call(req): Result  (takes an HTTP transport interface)

:app        Android
            ├─ net/StarlinkNetworks.kt   find or request the network to talk over
            ├─ net/OkHttpTransport.kt    implements the :protocol transport on a bound Network
            ├─ state/MiniStateDetector.kt  probes → SetupPending | Configured | Bypass | Rebooting | NotFound
            ├─ data/Profile.kt           saved Wi-Fi name + password (Keystore-encrypted)
            └─ ui/…                      Compose screens
```

The protocol client accepts a `Transport` interface; it does not open sockets itself.
Each suspend method returns `ClientResult.Ok(Response)`, `DeviceError(code, message)`,
or `TransportError(cause)`. It checks HTTP status, gRPC headers/trailers, embedded
device status, and the successful response's ID and kind. Malformed protobuf or
framing raises `ProtocolException` in the parsers and becomes a transport error in
the client. Cancellation propagates to the caller. Calls are never automatically
retried, including setup writes.

Unsigned 64-bit IDs, API versions, and uptime use Kotlin `Long` bit patterns.
Authentication responses expose only the auth kind; password fields are skipped.
`WifiGetStatusResponse.dhcpServers` preserves field 1019 as opaque length-delimited
bytes because its contents are not defined in the documented subset. Decoding
those contents requires further protocol documentation.

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

The Wi-Fi-off flow holds the connection that reported setup pending until the explicit
confirmation tap. It sends once and uses read-only probes for up to 60 seconds,
including the send time. Ethernet success requires silence at the original router
address, a readable dish at `192.168.100.1`, and a `100.64.0.0/10` address when IPv4
addresses are visible. A recreated Ethernet network must use the same cable adapter
interface. Any router HTTP response (even malformed or refused) prevents success.
Loss of the original setup Wi-Fi is labelled likely success, including when the write
reply was lost. A device refusal takes precedence over a dropped connection.
Leaving the foreground or recreating the activity during a write leads to reconnect
guidance; it never restores or retries the send. Reconnecting only reads the state.

The secure-Wi-Fi flow also pins the connection that reported setup pending. It saves
one profile encrypted with an Android Keystore AES-256-GCM key before sending a
single confirmed write. Names use 1–32 UTF-8 bytes and passwords use 8–63 printable
ASCII characters; these provisional limits still require hardware testing. Neither
activity saved state nor diagnostic strings contain credentials.

Verification reads `wifi_get_config` every 3 seconds for up to 3 minutes, including
the write time. It requires `setup_complete`, at least one BSS, and known secure
authentication on every BSS; masked passwords are never compared. Ethernet recovery
stays on the original adapter. If the setup Wi-Fi disappears, one temporary secured
`WifiNetworkSpecifier` request joins the chosen name/password for read-only checks.
Android may ask for Connect; the confirmation and progress screens explain this.
The bounded verification survives that system dialog covering the activity, but
activity destruction cancels it and recreation never restores a write.

After verification, the temporary request is released and the app explains the next
system step. Continue opens Android; Back opens the password and offline QR result.
The result also offers Join on this phone to repeat the phone-only save step. Then
API 30+ uses `ACTION_WIFI_ADD_NETWORKS` with a WPA2 suggestion; API 29 submits a
network suggestion and explains Android's approval notification. Saved, already
present, declined and unavailable results have separate messages. Saving a network
allows Android to auto-connect; the app does not claim that saving proves a connection.
Returning to Home can read a manually joined saved-profile Wi-Fi as well as STARLINK.
Only the existing local endpoint allowlist is used. If verification is interrupted,
reconnect guidance can reveal the encrypted saved credentials and QR without claiming
the Mini applied them. An encryption/storage failure prevents the write entirely.

## Quick-switch extras (later milestones)

- Quick Settings tile and home-screen shortcut that open straight into the pending-setup action.
- A "field card" screen showing the reset steps offline.

## Error handling

Map device answers to plain messages:

| Device answer | Message to the user |
|---|---|
| 9 `Wifi has already been set up` | "This Mini is already set up. Factory reset it first (reset icon on the back, hold until it clicks, ~3 s)." |
| 7 `Permission denied` | "The Mini refused this change. Only the official Starlink app can change a configured Mini." |
| timeout / no route | "Can't reach the Mini. Connect a cable or join the STARLINK Wi-Fi." |

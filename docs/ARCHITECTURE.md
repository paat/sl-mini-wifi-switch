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
            ├─ state/MiniStateDetector.kt  probes → SetupPending | Configured | Bypass | Unknown | Rebooting | NotFound
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

Probe order: each Ethernet candidate first (survives the Wi-Fi switching off), then setup Wi-Fi. A failed cable probe cannot hide an available STARLINK connection. Requested Wi-Fi waits for a DHCP gateway/default route within the request deadline. Home accepts a saved-name Wi-Fi only after its discovered gateway answers with a Starlink router identity; the saved name alone is not evidence. Read-only Home detection requires router silence, a readable dish, and an Ethernet CGNAT address before reporting Wi-Fi off. If either endpoint answers but setup cannot be determined, Home reports Unknown, refuses setup writes, and offers the existing reset guide. Discovery keeps Unknown as a fallback while checking other candidates; silence on another candidate cannot erase that answer. NotFound means neither endpoint answered (after any bounded restart wait). Configured, Bypass, and Unknown each explain that changing setup requires a factory reset and make reset help the primary action.

## Main flow

```
Home ─ detect state ─┬─ SetupPending ─▶ choose: [Wi-Fi off] or [Secure Wi-Fi (profile)]
                     │                     └─ confirm screen ─▶ send wifi_setup ─▶ verify ─▶ result
                     │                          result, Wi-Fi off: "connect the Mini to your router's WAN port"
                     │                          result, Wi-Fi on:  password in large type + Wi-Fi QR code
                     ├─ Configured  ─▶ Wi-Fi status, names, password availability; reset guide
                     ├─ Bypass      ─▶ Wi-Fi off, direct Starlink connection; reset guide
                     ├─ Unknown     ─▶ settings unreadable; reset guide, no setup choices
                     ├─ Rebooting   ─▶ wait with progress (poll every 3 s, up to 3 min)
                     └─ NotFound    ─▶ guide: cable or join STARLINK Wi-Fi, check reset steps
```

Verification after a write:

- **Bypass:** expect the router to stop answering and, on Ethernet, a CGNAT address within about 60 s. On Wi-Fi the connection drops; say so before sending.
- **Secure Wi-Fi:** poll `wifi_get_config` until `setup_complete` is true and the BSS auth is no longer `auth_open`. The router restarts once; tolerate 1–2 minutes of no answer.

The Wi-Fi-off flow holds the connection that reported setup pending until the explicit
confirmation tap. It sends once and uses read-only probes for up to 60 seconds,
including the send time. Ethernet success requires silence at the original router
address, a readable dish at `192.168.100.1`, and a `100.64.0.0/10` address. When IPv4 addresses are unavailable, the same router/dish evidence only permits likely success after an acknowledged or transmitted write. A recreated Ethernet network must use the same cable adapter
interface. Any router HTTP response (even malformed or refused) prevents success.
Loss of setup Wi-Fi is labelled likely success only after an acknowledged write or after the request body was transmitted; a replacement STARLINK handle prevents that inference. A device refusal takes precedence over a dropped connection.

A retained ViewModel owns flow state, the app-local network request, and bounded verification. Activity stop pauses Home/reset-guide polling only; the request stays owned by the ViewModel across rotation and temporary backgrounding. Finishing the app task clears the ViewModel and closes the request; explicit flow release also closes it. Rotation and backgrounding preserve an in-flight operation; neither creates another send. Returning to editing or confirmation keeps the form and re-probes existing networks before enabling Confirm. A missing connection offers Reconnect to the Mini; that tap first re-reads available networks in Ethernet-first order and requests Android’s Connect dialog only if none reaches the Mini. Declining it keeps the typed name and password. Connection failures and the Open phone settings action appear on the current editing or confirmation screen, as on Home. Screen changes and successful detection retire old connection feedback through one shared reset, retaining a permission denial only while access is still missing. Both confirmation choices use the same permission and connection gate. Probe and Wi-Fi jobs are assigned before they start, including with Main.immediate, so explicit navigation can cancel the active request. Intentional network release clears recent reachability only outside an active restart wait. Probe timeout and backgrounding preserve the original restart deadline. Process death never restores a write: interrupted progress opens reconnect guidance, and an unsent confirmation never claims a write failed.

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
The bounded verification survives the system dialog, backgrounding, and activity recreation. A declined or timed-out Connect request exposes a retry button. That button only rejoins and repeats read-only verification. Profile encryption and persistence run on the IO dispatcher before the confirmed write; storage failure prevents sending.

After verification, the temporary request is released and the app explains the next
system step. Continue opens Android; Back opens the password and offline QR result.
The result also offers Join on this phone to repeat the phone-only save step. Then
API 30+ uses `ACTION_WIFI_ADD_NETWORKS` with a WPA2 suggestion; API 29 submits a
network suggestion and explains Android's approval notification. Saved, already
present, declined and unavailable results have separate messages. Saving a network
allows Android to auto-connect; the app does not claim that saving proves a connection.
Configured Home shows Wi-Fi on/off, names, and password availability directly. It summarizes matching radio states and shows individual bands only when their states differ. Open networks say “No password”; secured networks explain that the Mini hides the password. **Show my Wi-Fi password** opens the existing saved-profile result only for a matching secured Wi-Fi name. Other Home states except SetupPending retain saved-profile recovery, including after Done, Back, or a fresh launch. The recovery action opens the name/password/QR result and Join on this phone without claiming the Mini applied the saved profile. SetupPending Home hides saved credentials because a reset makes them stale. Details keeps the last successful fields and refreshes config/dish fields from verification reads.
Only the existing local endpoint allowlist is used. If verification is interrupted,
reconnect guidance can reveal the encrypted saved credentials and QR without claiming
the Mini applied them. An encryption/storage failure prevents the write entirely.

## Quick-switch extras

The Quick Settings tile and static launcher shortcut open Home, which uses the
existing read-only detection before offering the two setup choices. The tile does
no network work and uses `startActivityAndCollapse(PendingIntent)` on API 34+.
The shortcut uses a separate, no-history launcher activity: Android clears the
static shortcut target task, so this activity forwards an explicit intent into the
existing app task. Warm and cold quick entry return only idle screens to Home. Editing, confirmation,
pending permission dialogs, progress, saved-password results and phone-save guidance
keep their current context. Quick entry never interrupts verification or sends another change. Each quick entry carries a unique launch ID. Saved instance state records the latest delivered launch ID, so recreation with that same intent preserves the flow while a new entry after process death still applies the idle-screen rule. onNewIntent stores the new intent before handling that entry, keeping the saved ID aligned with Android's next relaunch intent.

Home's **Start here** guide explains the physical reset, blinking-then-off light,
wait, and either cable connection or STARLINK plus Android's Connect dialog. Its
last step returns to existing state detection. Back retraces the selected path;
the step survives activity recreation without restoring any pending write.
**Reset help** is an offline card reachable from Home and every error presentation.
It explains that reset removes the saved Wi-Fi name/password. Home polls read-only
every 3 seconds while foregrounded. Reset help uses that same poll only when opened
from Home, an AlreadySetUp error, or a verified Wi-Fi result. This origin rule also
controls its top-of-guide waiting/progress text. All other help origins stay offline,
so Back preserves editing/confirmation evidence and result credentials.
The screen setter and start() share polling eligibility; an already running poll
survives navigation between eligible screens, including automatic return to Home.
Every read sets checking until it completes; the interval between reads does not.
stop() cancels polling. All Start here pages, editing, confirmations and writes
cancel Home polling.
A transition from a non-SetupPending observation into SetupPending automatically
opens Home from eligible Reset help; no tap or write is involved. Repeated
SetupPending observations leave the guide open.
Home retains its three-minute restart deadline, including across stop/start;
NotFound ends Home polling and offers Check again. Eligible Reset help continues
polling every 3 seconds even after NotFound, until navigation or backgrounding.
Its waiting text stays at the top without recreating the scrollable column when
observations change. Reset help has only Back, which preserves its origin.
Guide step changes start at the top, including Back and alternate paths.
Home keeps restart waiting prominent without a Start here reset action while polling.
The Start here pages remain offline and do not start network work.

SetupPending Home says **Ready to set up** and explains that the Mini was reset.
Its two large choices explain cable-only use or a Wi-Fi name/password; Details is
a small secondary action. Start here, Reset help, Check again and the read-only
footer are absent in this state.

All illustrations are project-drawn bundled vectors, animated as separate vector
layers. The reset press holds for three seconds; the illustrative light sequence
ends off. Readiness is established by detection, never by an off light alone.
Android's Remove animations setting is observed while screens are open and shows
still final poses; waiting uses a static clock and text without a partial progress bar. Shared wait
presentation supplies the expected time and recovery instruction; existing bounded
state and setup verification policies remain unchanged.

## Error handling

Map device answers to plain messages:

| Device answer | Message to the user |
|---|---|
| 9 `Wifi has already been set up` | "This Mini is already set up. Factory reset it first (reset icon on the back, hold until it clicks, ~3 s)." |
| 7 `Permission denied` | "The Mini refused this change. Only the official Starlink app can change a configured Mini." |
| timeout / no route | "Can't reach the Mini. Connect a cable or join the STARLINK Wi-Fi." |

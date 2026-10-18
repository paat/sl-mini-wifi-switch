# sl-mini-wifi-switch

An open-source Android app that switches a **Starlink Mini** between two field modes, without the official app and without an account sign-in:

| Mode | What the Mini does | When to use it |
|---|---|---|
| **Wi-Fi off** (bypass) | Built-in Wi-Fi and router are off. The Ethernet port hands one wired device an internet address straight from Starlink. | Semi-permanent spot where you want a small RF footprint. |
| **Secure Wi-Fi** | Built-in router on, Wi-Fi with your own name and password. | Quick stop: power bank in, phone or laptop on Wi-Fi, pack up. |

> **Status: pre-alpha, M3 software implemented.** The app finds the Mini, shows read-only details, and offers Wi-Fi off or Wi-Fi on with a password only while setup is pending. Every setup write requires an explicit confirmation and is sent once. Secure Wi-Fi includes encrypted profile reuse, a password/QR result, and Android network saving. Protocol writes were verified separately by a human (see [docs/FINDINGS.md](docs/FINDINGS.md)); app hardware checks and password-limit checks remain pending.

## Build

Use JDK 21 and an Android SDK with platform 37 and Build Tools 36.0.0. Set
`ANDROID_HOME` to the SDK directory, or put `sdk.dir=/path/to/android-sdk` in
an untracked `local.properties` file. The wrapper downloads Gradle automatically.

```bash
./gradlew --no-daemon clean build test
```

Record the phone screenshots with Robolectric and Roborazzi (JVM; no emulator or KVM):

```bash
./gradlew --no-daemon :app:recordRoborazziDebug --tests '*ScreenshotsTest'
```

The task writes committed PNGs to [`docs/screenshots/m1/`](docs/screenshots/m1/),
[`docs/screenshots/m2/`](docs/screenshots/m2/), and [`docs/screenshots/m3/`](docs/screenshots/m3/).
M3 includes generated/own-password setup with and without a saved profile, 200% setup
fonts, confirmation, progress, password/QR results and Android connection explanations
in light and dark themes. Long screens include scrolled views. See the
[M3 PNG list](docs/screenshots/m3/manifest.txt) and [test counts](docs/screenshots/m3/verification.txt).
Normal `build test` runs render and action assertions without recording or comparing
pixels, so CI font differences do not fail the suite. App unit tests run on the JVM; screenshot tests use a debug-only Compose host.

The app uses sockets on the selected network, with no process-wide binding,
DNS lookups, redirects, proxies, analytics, or remote services. It reads only
`wifi_get_config`, `get_device_info`, and router `get_status`; confirmed setup sends
`wifi_setup` once. Android may ask
for permission to connect to local devices (Android 17+), nearby Wi-Fi devices
(Android 13+), or location to identify the existing STARLINK network. Location
services may need to be enabled for Wi-Fi identification on the phone; no
physical location is collected. Permission denial has a phone-settings recovery
button. App-local Wi-Fi requests are released on leaving the foreground except
during bounded secure setup verification, so Android’s Connect dialog can complete.
Verification releases the temporary connection before system-wide network saving.

The `:protocol` module is pure Kotlin/JVM; `:app` is the Android Compose app.
Dependency, plugin, and SDK versions live in `gradle/libs.versions.toml`; the
wrapper distribution and checksum are pinned in `gradle/wrapper/gradle-wrapper.properties`.

## How it works

Starlink routers refuse configuration changes from anything except the signed-in Starlink app. There is one exception: **right after a factory reset**, the router waits for its first setup and accepts an unauthenticated setup request on its local API. That request has exactly four options: Wi-Fi name, Wi-Fi password, skip, or bypass.

So every switch starts with a factory reset (press the reset icon on the back of the Mini until it clicks, hold about 3 seconds), and the app then finishes the setup in the mode you want:

```
            factory reset (hardware, ~2 min)
 any mode ───────────────────────────────▶ setup pending (open Wi-Fi "STARLINK", 192.168.1.1)
                                                │
                    app: setup{bypass}          │          app: setup{name, password}
             ┌──────────────────────────────────┴───────────────────────────────┐
             ▼                                                                  ▼
      Wi-Fi off (bypass)                                                Secure Wi-Fi
```

The phone reaches the Mini either over its **open setup Wi-Fi** or over **Ethernet** (USB-C Ethernet adapter on the phone, cable to the Mini's port).

What this app can't do, because the router refuses it: turn Wi-Fi off while keeping the router on, change the local network range (subnet; it is always 192.168.1.x after a reset), or change anything on an already configured Mini. All were tested (see findings).

## Built for non-technical users

The app must be foolproof and usable in the field by people with no technical background, with illustrated step-by-step guides for every physical step (resetting the Mini, connecting a cable). The requirements are in [docs/UX.md](docs/UX.md).

## Documentation

| File | Contents |
|---|---|
| [AGENTS.md](AGENTS.md) | Start here if you are a coding agent: goals, rules, where to begin |
| [docs/UX.md](docs/UX.md) | User experience requirements: foolproof, non-technical, visual guides |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Local API: transport, framing, message and field numbers, errors |
| [docs/FINDINGS.md](docs/FINDINGS.md) | What was tested on real hardware, with results |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Planned Android app design |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Milestones, tasks and open questions |
| [docs/TESTING.md](docs/TESTING.md) | Unit-test vectors and the hardware test procedure |
| [proto/sl_mini_subset.proto](proto/sl_mini_subset.proto) | Minimal hand-written schema with only the fields this app needs |
| [tools/vectors/](tools/vectors/) | Byte-exact request and response examples |

## Disclaimer

This is an unofficial, independent project. It is not affiliated with, endorsed by, or supported by SpaceX or Starlink. "Starlink" is a trademark of SpaceX, used here only to describe compatible hardware. The app uses the local setup request that the router itself offers after a factory reset; it does not bypass authentication or modify firmware. Using it resets your router settings. Use at your own risk.

## License

[Apache-2.0](LICENSE)

# SL Mini Wi-Fi Switch

Android app (Kotlin, Compose) that finishes setup of a freshly factory-reset Starlink Mini in either bypass (Wi-Fi off) or secure Wi-Fi (name and password), over the Mini's open setup Wi-Fi or Ethernet, plus a read-only status screen. Users are non-technical people in the field, so the app must be foolproof and guide every physical step with illustrations. [docs/UX.md](docs/UX.md) is binding: a screen that doesn't meet it isn't done.

## Commands

- Build and test: `./gradlew build test :app:verifyRoborazziDebug` (what CI runs).
- Protocol tests only: `./gradlew :protocol:test`; one test: `./gradlew :protocol:test --tests '*RequestsTest'`.
- App unit tests: `./gradlew :app:testDebugUnitTest`.
- Re-record phone screenshots after a UI change (Robolectric + Roborazzi, no emulator): `./gradlew --no-daemon :app:recordRoborazziDebug --tests '*ScreenshotsTest'`; details in README.md. `verifyRoborazziDebug` fails when a committed PNG drifts.
- Full local gate before a PR: `./gradlew :protocol:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug`.

## Layout

- `:protocol` is pure Kotlin/JVM (wire format, gRPC-web framing, request builders, response parsers, `MiniClient` over a `Transport` interface). `:app` is Android (`net/`, `state/`, `data/`, `ui/`). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- `proto/sl_mini_subset.proto` is the minimal schema; `tools/vectors/*.hex` are byte-exact request/response fixtures.
- Kotlin, Compose, Material 3, minSdk 29. Gradle Kotlin DSL with `gradle/libs.versions.toml`. User-facing text lives in `strings.xml` (English default, Estonian follows the phone language; see [docs/TRANSLATION-et.md](docs/TRANSLATION-et.md)), plain wording for someone in the field.

## Hard rules

1. Never send a write request to real hardware yourself. Writes are `wifi_setup`, `wifi_set_config` and every request marked write in [docs/PROTOCOL.md](docs/PROTOCOL.md). A human runs hardware tests with the Mini in reach, following [docs/TESTING.md](docs/TESTING.md).
2. Never send `factory_reset`, `dish_factory_reset`, or any request the project doesn't document.
3. Every write in the app needs an explicit user tap and a confirmation step that says what will happen ("Wi-Fi will turn off. Getting it back needs a factory reset.").
4. The `:protocol` module has no Android imports, so it runs in JVM tests against `tools/vectors/`. Every request builder has a test that matches its vector byte for byte.
5. Don't ship SpaceX's full protobuf schema; keep the minimal subset with field numbers observed on the device.
6. No network calls outside the local Starlink addresses (`192.168.1.1`, `192.168.100.1`, and the router address found via DHCP). No analytics or crash reporting.
7. Never log a Wi-Fi password. Store it only with Android Keystore-backed encryption; show it only where UX.md says (show/hide toggle, result screen, Wi-Fi QR code).
8. No technical terms in the main interface (bypass, subnet, gRPC, API, setup request); wording rules are in UX.md.

## Non-goals

- Changing settings on a Mini that is already set up (the router refuses it without the official app's signed requests; don't forge or replay them).
- Triggering a factory reset from the app; the reset stays a physical action.
- Dish commands (stow, reboot, GPS, power save) or anything sent to `192.168.100.1` beyond read-only status.
- Other Starlink hardware before the Mini works.

## Workflow

- Done for a change means: `./gradlew build test` passes on a clean checkout, new behaviour has unit tests, new screens meet UX.md and include light and dark phone screenshots in the PR, and docs are updated (ROADMAP.md tick, FINDINGS.md, PROTOCOL.md as relevant).
- Anything needing a hardware test is listed in the PR description with the exact steps from TESTING.md.
- Update [docs/FINDINGS.md](docs/FINDINGS.md) whenever a human hardware test confirms or disproves something, with date, firmware versions and the exact result.

## Docs

[docs/UX.md](docs/UX.md), [docs/PROTOCOL.md](docs/PROTOCOL.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/ROADMAP.md](docs/ROADMAP.md), [docs/TESTING.md](docs/TESTING.md), [docs/FINDINGS.md](docs/FINDINGS.md).

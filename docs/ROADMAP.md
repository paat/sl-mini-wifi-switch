# Roadmap

Tick tasks as they land. Each milestone ends in a working build; see the definition of done in [AGENTS.md](../AGENTS.md).

## M0: Project and protocol library

- [x] Gradle project with `:protocol` (JVM) and `:app` (Android), version catalog, Apache-2.0 headers optional
- [x] CI on GitHub Actions: `./gradlew build test` on push and PR
- [x] `:protocol` varint and length-delimited encode/decode
- [x] gRPC-web framing: build request frame; parse data frames, trailer frame, and header-only status
- [x] Request builders for every supported request vector, each with a byte-exact unit test (`wifi_set_config_disable_radios` remains parser-only test input)
- [x] Response parsers for `WifiConfig` subset, `DeviceInfo`, `Status`; test against `wifi_setup.response_ok.hex` and a synthetic config message

## M1: Read-only app

- [x] Network discovery on Ethernet and the `STARLINK` Wi-Fi (see ARCHITECTURE.md), with OkHttp bound to the chosen network
- [x] State detection: SetupPending, Configured, Bypass, Rebooting, NotFound
- [x] Status screen: router and dish versions, SSIDs, auth type, LAN subnet, WAN address
- [ ] Hardware test by a human (TESTING.md, read-only section)

## M2: Wi-Fi off (bypass)

- [ ] Confirm screen that states the consequences (UX.md, Wi-Fi off specifics)
- [ ] Send `wifi_setup{bypass}` and verify as described in ARCHITECTURE.md
- [ ] Result screen: connect the Mini's cable to the router's internet (WAN) port, with illustration; how to undo
- [ ] Hardware test by a human, over Ethernet and over the setup Wi-Fi

## M3: Secure Wi-Fi

- [x] Hardware-test `network_password` (FINDINGS.md test 10: works, WPA2/WPA3)
- [x] Hardware-test a client joining with the scripted password (FINDINGS.md test 11)
- [ ] Hardware-test password limits (min/max length, special characters)
- [ ] Profile storage with Keystore-backed encryption; "Use the same Wi-Fi as last time"
- [ ] Generated password by default, show/hide toggle, own-password option
- [ ] Send `wifi_setup{network_name, network_password}`, verify `setup_complete` and auth type
- [ ] Input validation matching what the device accepts, rules shown before typing
- [ ] Result screen with the password in large type and a Wi-Fi QR code

## M4: Field use and visual guides

- [ ] Illustrations drawn for the project (vector / animated vector drawables) for every physical step in UX.md: reset symbol location and press, light behaviour, cable and adapter, joining STARLINK, router WAN port
- [ ] Guided step-by-step flow from the home screen, using those illustrations
- [ ] Quick Settings tile and app shortcut into the pending-setup action
- [ ] Offline help screen with the reset steps
- [ ] Clear progress and timeouts during the 1–2 minute reboots
- [ ] Field-condition checks: 200% font size, sunlight contrast, TalkBack, 64 dp primary buttons

## M5: Release

- [ ] Acceptance test with at least three non-technical people (UX.md); fix every hesitation found
- [x] App icon
- [ ] Screenshots
- [x] F-Droid metadata
- [ ] Signed release builds via GitHub Releases
- [ ] Make the repository public

## Open questions

- Does the setup window time out, and does the Mini work normally while waiting?
- Does the bypass response arrive before the Wi-Fi drops when sent over Wi-Fi?
- What do other Starlink routers (Gen 3 standard, Gen 2) do with the same requests?
- Does the official app's "allow access on local network" setting change what the local API answers?

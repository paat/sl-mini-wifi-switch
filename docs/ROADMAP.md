# Roadmap

Tick tasks as they land. Each milestone ends in a working build; see the definition of done in [AGENTS.md](../AGENTS.md).

## M0: Project and protocol library

- [ ] Gradle project with `:protocol` (JVM) and `:app` (Android), version catalog, Apache-2.0 headers optional
- [ ] CI on GitHub Actions: `./gradlew build test` on push and PR
- [ ] `:protocol` varint and length-delimited encode/decode
- [ ] gRPC-web framing: build request frame; parse data frames, trailer frame, and header-only status
- [ ] Request builders for every file in `tools/vectors/*.request.hex`, each with a byte-exact unit test
- [ ] Response parsers for `WifiConfig` subset, `DeviceInfo`, `Status`; test against `wifi_setup.response_ok.hex` and a synthetic config message

## M1: Read-only app

- [ ] Network discovery on Ethernet and the `STARLINK` Wi-Fi (see ARCHITECTURE.md), with OkHttp bound to the chosen network
- [ ] State detection: SetupPending, Configured, Bypass, Rebooting, NotFound
- [ ] Status screen: router and dish versions, SSIDs, auth type, LAN subnet, WAN address
- [ ] Hardware test by a human (TESTING.md, read-only section)

## M2: Wi-Fi off (bypass)

- [ ] Confirm screen that states the consequences
- [ ] Send `wifi_setup{bypass}` and verify as described in ARCHITECTURE.md
- [ ] Hardware test by a human, over Ethernet and over the setup Wi-Fi

## M3: Secure Wi-Fi

- [ ] **First, hardware-test `network_password`** with a human (TESTING.md) and record the result in FINDINGS.md: auth type, length limits
- [ ] Profile storage with Keystore-backed encryption
- [ ] Send `wifi_setup{network_name, network_password}`, verify `setup_complete` and auth type
- [ ] Input validation matching what the device accepts

## M4: Field use

- [ ] Quick Settings tile and app shortcut into the pending-setup action
- [ ] Offline help screen with the reset steps
- [ ] Clear progress and timeouts during the 1–2 minute reboots

## M5: Release

- [ ] App icon, screenshots, F-Droid metadata
- [ ] Signed release builds via GitHub Releases
- [ ] Make the repository public

## Open questions

- Does the setup window time out, and does the Mini work normally while waiting?
- Does the bypass response arrive before the Wi-Fi drops when sent over Wi-Fi?
- What do other Starlink routers (Gen 3 standard, Gen 2) do with the same requests?
- Does the official app's "allow access on local network" setting change what the local API answers?

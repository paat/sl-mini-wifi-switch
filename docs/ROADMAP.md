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

- [x] Confirm screen that states the consequences (UX.md, Wi-Fi off specifics)
- [x] Send `wifi_setup{bypass}` and verify as described in ARCHITECTURE.md
- [x] Result screen: connect the Mini's cable to the router's internet (WAN) port, with illustration; how to undo
- [ ] Hardware test by a human, over Ethernet and over the setup Wi-Fi

## M3: Secure Wi-Fi

- [x] Hardware-test `network_password` (FINDINGS.md test 10: works, WPA2/WPA3)
- [x] Hardware-test a client joining with the scripted password (FINDINGS.md test 11)
- [ ] Hardware-test password limits (min/max length, special characters)
- [x] Profile storage with Keystore-backed encryption; "Use the same Wi-Fi as last time"
- [x] Generated password by default, show/hide toggle, own-password option
- [x] Send `wifi_setup{network_name, network_password}`, verify `setup_complete` and auth type
- [x] Provisional input validation (name 1–32 UTF-8 bytes; password 8–63 printable ASCII), rules shown before typing; device limits remain a hardware test
- [x] Result screen with the password in large type and a Wi-Fi QR code
- [x] Save new Wi-Fi through Android (API 30+ Save dialog; API 29 suggestion notification), with plain explanations and QR fallback
- [x] JVM tests and light/dark/200% setup Roborazzi screenshots in `docs/screenshots/m3/`
- [ ] Human app test over Ethernet and setup Wi-Fi, including auto-connect on API 30+ and API 29 if available

## M4: Field use and visual guides

- [x] Project-drawn vectors for every physical step: reset symbol/three-second press, blinking-then-off light, cable and adapter, joining STARLINK, Android Connect dialog, router WAN port, restart; described and still when animations are removed
- [x] Guided step-by-step flow from Home through reset, wait, connection and existing detection, with Back/Next and cable/Wi-Fi alternatives
- [x] Quick Settings tile and app shortcut into Home detection and the pending-setup choices; API 34+ PendingIntent and safe task reuse
- [x] Offline reset help from Home and every error presentation, with Back and Check again
- [x] Shared visible progress with expected times and recovery guidance during reboots; existing bounded verification unchanged
- [x] Software field-condition checks: scrollable 200% text, WCAG AA body contrast in both themes, labelled illustrations, 64 dp primary buttons and 48 dp touch targets
- [x] JVM/Compose coverage and light/dark/200% guide/help/wait screenshots in `docs/screenshots/m4/`
- [ ] Human field checks: TalkBack walkthrough, sunlight/glove readability, reduced motion and illustration accuracy, Quick Settings tile and shortcut on real phones (TESTING.md M4)

## Robustness follow-up (#11, #14, #17)

- [x] Conservative state/write evidence, network fallback and bounded DHCP readiness
- [x] Retained verification across rotation/backgrounding; fresh reads before returning to Confirm
- [x] Last-known Details, saved password access from Home, explicit read-only reconnect retry
- [x] IO profile persistence, plain input rules, regression tests and affected light/dark screenshots
- [ ] Human lifecycle/reconnect checks below the M2/M3 procedures in TESTING.md

## PR #18 tribunal round 1

- [x] Preserve result credentials through reset help and warm/cold quick entry, including pending permission prompts (T-001, T-003, T-007, T-008)
- [x] Reset guide scroll on step changes and keep the reset animation released after its three-second hold (T-002, T-004)
- [x] Keep reboot polling prominent and use a static clock/text wait for reduced motion (T-005, T-006)

## PR #18 tribunal round 2

- [x] Keep setup evidence after profile-save failure so retry can send once (T-001)
- [x] Consume quick-launch actions so rotation keeps guide and error screens (T-002)
- [x] Preserve the reboot deadline through probe timeout and lifecycle release (T-003, T-005)
- [x] Keep the typed form on return and request setup Wi-Fi only after a Reconnect tap (T-004)

## Configured Home status (#22)

- [x] Home quick status for configured Wi-Fi, direct Starlink connection with Wi-Fi off, and unreadable settings; prominent existing reset help in every case
- [x] Summarized radio state, Wi-Fi names, open/hidden password wording, and saved-password access only for a matching secured name on configured Home
- [x] Unknown detection and conservative setup refusal, regression tests, light/dark/200% status screenshots in `docs/screenshots/m5-status/`
- [ ] Human read-only Home and TalkBack checks (TESTING.md #22)

## Connection lifecycle (#19)

- [x] Keep app-requested STARLINK connections in the ViewModel across Activity recreation; release on task finish or explicit flow release
- [x] Show connection failures and Open phone settings on editing and both confirmation screens
- [x] Consume quick entry once across ActivityScenario recreation, with the latest delivered launch intent
- [x] Re-read Ethernet before requesting Wi-Fi from Reconnect; regression tests for all four cases

## PR #28 tribunal cycle 1 (#19)

- [x] Distinguish a new cold quick entry from restoration of the same intent (T-001)
- [x] Keep an active Reconnect probe and its Wi-Fi fallback across stop/start (T-002)
- [x] Clear old connection failures and unneeded Wi-Fi permission feedback after a successful cable read (T-003)
- [x] Share SecureWifi permission requirements between confirmation and resume (T-004)
- [x] Use Reconnect wording on editing and confirmation screens, retaining Check again on Home (T-005)

## PR #28 tribunal cycle 2 (#19)

- [x] Assign connection jobs before immediate execution so leaving the flow cancels the Wi-Fi fallback (T-006)
- [x] Store each new launch intent before handling it; restore with the latest delivered launch ID (T-007)
- [x] Centralize feedback expiry on screen changes and successful detection; share both confirmation permission gates and one button-independent permission sentence (T-008, T-009)
- [x] Remove the unused setupLaunchHandled test key (T-010)

## Configuration-first Home (#32)

- [x] “Ready to set up” Home with explained choices, secondary Details and fewer actions
- [x] Foreground read-only polling on Home and Reset help; automatic return to setup choices after reset
- [x] Restart progress and stopped-check retry only; updated guide wording and icon description
- [x] Fake-transport reset sequence, lifecycle/navigation regression tests and affected Roborazzi baselines
- [x] Review cycle 1: transition-only reset-help navigation with origin preservation; hide stale saved credentials on SetupPending Home
- [x] Review cycle 2: share reset-help polling eligibility, keep checking after NotFound, retain the active poll across navigation, and preserve working Confirm after Back (T-001, T-002, T-003)
- [x] Review cycle 3: quiet background polls with stable setup choices; persist reset-help auto-detection across process death; remove duplicate Home-as-Details captures (T-004, T-005, T-006)
- [ ] Human reset/reconnect and TalkBack checks (TESTING.md #32)

## Estonian language (#33)

- [x] Plain Estonian UI and store text; English fallback and Android 13+ per-app language selection
- [x] Package only English and Estonian; lint fails for missing translations
- [x] Estonian normal/200% light screenshots for seven main screens, with text-fit and touch-target checks
- [x] Translation glossary and wording questions in TRANSLATION-et.md
- [ ] Independent second-model translation review before merge; owner review of flagged wording

## M5: Release

- [ ] Acceptance test with at least three non-technical people (UX.md); fix every hesitation found
- [x] App icon
- [ ] Screenshots
- [x] F-Droid metadata
- [x] Signed release builds via GitHub Releases
- [x] Make the repository public
- [x] v0.1.0 pre-release published (tag v0.1.0)
- [ ] v0.1.1 pre-release published (tag v0.1.1)

## Open questions

- Does the setup window time out, and does the Mini work normally while waiting?
- Does the bypass response arrive before the Wi-Fi drops when sent over Wi-Fi?
- What do other Starlink routers (Gen 3 standard, Gen 2) do with the same requests?
- Does the official app's "allow access on local network" setting change what the local API answers?

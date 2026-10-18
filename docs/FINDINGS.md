# Hardware findings

A log of what was tested on real hardware. Add new entries at the top of each section with the date and firmware versions. Never delete a result; mark it superseded instead.

## Test setup (2026-10-18)

| Item | Value |
|---|---|
| Kit | Starlink Mini (dish `hardware_version: mini1_prod1`) |
| Dish firmware | `2026.08.31.cr85832` |
| Router (built in) | `hardware_version: v4`, running `2025.06.23.mr55202` (other boot slot `2026.10.07.mr87930`), `api_version` 95 and 132 seen |
| Subscription | inactive (satellite link and PoP reachable, no internet) |
| Phone | OnePlus 11, LineageOS 23.2 (Android 16), USB-C hub with ASIX AX88179A/B Ethernet |
| Client | `adb shell` on the phone, `curl --interface eth0` (or `wlan0`) to bind to one interface |

## Results

| # | Test | Path | Result |
|---|---|---|---|
| 1 | `wifi_get_config`, `get_device_info`, `get_status` | Ethernet, configured router | **Works without sign-in.** Full config readable, including SSIDs and auth types. |
| 2 | `wifi_set_config` (no-op: apply flags only) | Ethernet, configured | Refused: 7 `Permission denied` |
| 3 | `wifi_set_config` (no-op) | Starlink Wi-Fi (`wlan0`), configured | Refused: 7 `Permission denied`. Wi-Fi vs Ethernet makes no difference. |
| 4 | `wifi_set_config` bypass_mode=true | Ethernet, configured | Refused: 7 |
| 5 | `wifi_setup{bypass=true}` | Ethernet, configured | Refused: 9 `Wifi has already been set up`. Note: not a permission error. |
| 6 | Factory reset, then `wifi_get_config` | Ethernet | Router at `192.168.1.1`, `setup_complete` absent, two open BSS named `STARLINK` (2.4 and 5 GHz). |
| 7 | `wifi_setup{bypass=true}` right after reset | Ethernet | **Works.** Status 0. Within 30 s: Starlink Wi-Fi gone, phone `eth0` got `100.76.233.16/10` and a public IPv6 `/64`, dish answers at `192.168.100.1`, router address no longer answers. |
| 8 | Factory reset from bypass, then `wifi_setup{network_name="camera2.4"}` | Ethernet | **Works.** Status 0. Router restarted (`CONFIG_UPDATE`), then `setup_complete: true`, SSID `camera2.4` on both bands, still `auth_open` (no password sent). Network visible in the phone's Wi-Fi list. |
| 9 | Factory reset, then `wifi_set_config` disable 2.4/5/5-high radios (before setup) | Ethernet | Refused: 7 `Permission denied`. "Router on, Wi-Fi off" is not reachable. |
| 10 | Still pending after test 9: `wifi_setup{network_name="sl-test", network_password=<16 random letters and digits>}` | Ethernet | **Works.** Status 0. Router answered again after about 50 s with `setup_complete: true`, SSID `sl-test` on 2.4 and 5 GHz, both `auth_wpa2_wpa3`. The unauthenticated `wifi_get_config` returns the password masked as `•••••`, not the real value. |

## Not yet tested

- A client actually joining a Wi-Fi set up by script (test 10) with the password.
- Password limits: shortest and longest accepted, special characters, what happens with fewer than 8 characters.
- `wifi_setup` sent over the open `STARLINK` Wi-Fi instead of Ethernet (expected to behave the same, see test 3).
- Whether the bypass response arrives before the Wi-Fi drops when sent over Wi-Fi.
- `skip = true`.
- How long the setup window stays open, and whether the Mini can be used normally while setup is pending.
- Internet access in bypass with an active subscription.
- Other hardware (Gen 3 standard router, Gen 2) and newer firmware.

## Phone-side notes

- Android treats the Mini's networks as "no internet" while the subscription is inactive. Apps must bind sockets to the Starlink network explicitly; the default network stays on mobile data or another Wi-Fi.
- On this phone the ASIX AX88179 USB Ethernet driver (`ax_usb_nic`) often fails to bring the interface up (`page allocation failure: order:4` in `ax_open`). A phone restart with the adapter plugged in works around it. Realtek RTL8153/8156 adapters are recommended.
- `cmd wifi start-scan` returns stale results while the screen is off; check the `Age(sec)` column.

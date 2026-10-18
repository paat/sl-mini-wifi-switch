# Testing

## Unit tests (no hardware)

`tools/vectors/*.hex` hold frames as space-separated hex bytes:

| File | What it is |
|---|---|
| `get_device_info.request.hex` | read request, id 1 |
| `wifi_get_config.request.hex` | read request, id 1 |
| `wifi_get_status.request.hex` | read request (`get_status`), id 1 |
| `wifi_setup_bypass.request.hex` | write: setup with bypass, id 1 (sent to a device with id 4, otherwise identical) |
| `wifi_setup_name.request.hex` | write: setup with name `camera2.4`, id 1 (sent to a device with id 6) |
| `wifi_setup_name_password.request.hex` | write: name `ExampleNet`, password `example-pass-123`, id 1 (**never sent to a device**) |
| `wifi_set_config_disable_radios.request.hex` | write the device refuses; for parser and error-path tests only |
| `wifi_setup.response_ok.hex` | captured success response: data frame (id 4, api_version 132, empty `wifi_setup`) + trailer `grpc-status: 0` |

Every request builder must reproduce its vector byte for byte.

## Hardware tests (a human with the Mini in reach)

Rules: a person runs these, with the Mini within reach for a factory reset. An agent may prepare commands but must not send write requests to a device itself.

### Setup

- Phone with USB debugging and a USB-C Ethernet adapter cabled to the Mini, or joined to the Mini's Wi-Fi.
- Push vectors: convert a `.hex` file to binary and `adb push` it to `/data/local/tmp/`.
- Send from the phone so the request leaves through the right interface:

```sh
curl --interface eth0 -s -m 15 \
  -H "content-type: application/grpc-web+proto" -H "x-grpc-web: 1" \
  --data-binary @/data/local/tmp/wifi_get_config.bin \
  -D - -o /data/local/tmp/resp.bin \
  http://192.168.1.1:9001/SpaceX.API.Device.Device/Handle
```

Use `--interface wlan0` to test over Wi-Fi. Check the `Grpc-Status` header and the trailer at the end of `resp.bin`.

### Read-only checks (safe any time)

1. `wifi_get_config` → status 0; note `setup_complete`, SSIDs, auth.
2. `get_device_info` to the router (port 9001) and to the dish (`192.168.100.1:9201`).

### Write checks (each needs a factory reset first)

Factory reset: press the reset icon on the back of the Mini until it clicks, hold about 3 s until the LED blinks fast and goes off. Wait until the router answers at `192.168.1.1` (35–110 s seen) and `setup_complete` is false.

| Test | Send | Expect |
|---|---|---|
| Bypass | `wifi_setup_bypass` | status 0; Wi-Fi gone; Ethernet gets `100.64.0.0/10` |
| Name only | `wifi_setup_name` | status 0; router restarts; SSID set, `auth_open` |
| Name + password | a frame with your own name and password | **record result in FINDINGS.md**: status, auth type |

Restore the Mini to the state you want afterwards (another reset plus setup, or the official app).

### M2 app: Bypass write check over both connections (human only)

Run the following twice, with a fresh physical factory reset before **each** run:

1. **Ethernet run:** connect the phone's USB-C Ethernet adapter to the Mini.
   **Setup Wi-Fi run:** unplug the phone's Ethernet adapter and join `STARLINK`
   (or use the app's Join STARLINK action and Android's Connect confirmation).
2. Press the reset icon on the back until it clicks; hold about 3 seconds until the
   LED blinks fast and goes off. Wait for `192.168.1.1` to answer (35–110 seconds
   observed), with `setup_complete` false. The app must say “Your Mini is ready”.
3. Choose Wi-Fi off. Check the no-Wi-Fi/no-local-network warning, router requirement,
   and reset-to-undo wording. On Wi-Fi, also check the expected disconnection warning.
   Tap Cancel first: the Mini must remain pending with STARLINK available.
4. Choose Wi-Fi off again and tap **Turn Wi-Fi off** once. This is the Bypass
   `wifi_setup_bypass` write from the table above; do not also send the curl vector.
   Expect status 0 if the response arrives, visible progress, and Wi-Fi disappearing.
5. **Ethernet:** within about 60 seconds, expect an address in `100.64.0.0/10`,
   no router answer at `192.168.1.1:9001`, and read-only dish info at
   `192.168.100.1:9201`. Expect “Wi-Fi is off”.
   **Setup Wi-Fi:** expect the phone to disconnect and a result explaining that
   Wi-Fi is likely off. Note whether the response arrived before the disconnection.
   Connect Ethernet to check the same address/router/dish evidence independently.
6. Follow the result illustration: move the Mini's cable to the separate router's
   WAN/Internet/globe-labelled port. Check the port-label note and undo instructions.
   Interrupting verification or losing the connection without success evidence must
   offer reconnect/check guidance without automatically sending another write.
7. Restore the desired state with another physical reset plus setup or the official
   app. Record date, dish/router firmware, response status (or lost response), timing,
   and exact observations in FINDINGS.md. These M2 app checks remain unperformed
   until a human records them; the earlier protocol-only Ethernet test is not a substitute.

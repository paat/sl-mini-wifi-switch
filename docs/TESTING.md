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

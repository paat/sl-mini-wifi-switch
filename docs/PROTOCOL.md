# Local API protocol

Everything here was observed on one Starlink Mini on 2026-10-18 (versions in [FINDINGS.md](FINDINGS.md)). Field numbers come from the router's own gRPC server reflection. Treat anything not marked **verified** as an assumption to test.

## Endpoints

| Device | Address | Port | Protocol | Used by this app |
|---|---|---|---|---|
| Router (built into the Mini) | `192.168.1.1` after a factory reset; otherwise the LAN gateway | 9001 | gRPC-web over HTTP/1.1, plaintext | yes |
| Router | same | 9000 | gRPC over HTTP/2 (h2c), plaintext | no (Android HTTP stacks handle gRPC-web more easily) |
| Dish | `192.168.100.1` (fixed, reachable in router and bypass mode) | 9201 | gRPC-web | read-only status |
| Dish | `192.168.100.1` | 9200 | gRPC | no |

The LAN subnet resets to `192.168.1.0/24` on factory reset (**verified**). Users may have changed it before (e.g. `192.168.2.0/24`), so for a configured Mini use the DHCP gateway of the network the phone is on.

## Transport (verified)

One RPC handles everything: `POST /SpaceX.API.Device.Device/Handle`.

Request headers:

```
Content-Type: application/grpc-web+proto
X-Grpc-Web: 1
```

Request body is one gRPC-web frame:

```
00                  flags (0 = data)
LL LL LL LL         big-endian length of the protobuf message
<Request protobuf>
```

Response, success: HTTP 200, body = data frame (`00` + length + `Response` protobuf), then a trailer frame (`80` + length + ASCII `grpc-status: 0\r\n`).

Response, error: HTTP 200 with **empty body** and the status in headers ("trailers-only"):

```
Grpc-Status: 7
Grpc-Message: Permission denied
```

A client must check both places: the `Grpc-Status` header, and the trailer frame in the body.

## Messages (subset)

Names below are ours, field numbers are the device's. Full schema: [proto/sl_mini_subset.proto](../proto/sl_mini_subset.proto).

### Request (`SpaceX.API.Device.Request`)

| Field | No. | Type | Notes |
|---|---|---|---|
| id | 1 | uint64 | Any value; echoed in the response |
| get_device_info | 1008 | empty message | read |
| get_status | 1004 | empty message | read; on the router returns `WifiGetStatusResponse` |
| wifi_get_config | 3009 | empty message | read |
| wifi_setup | 3003 | `WifiSetupRequest` | **write**; works only while setup is pending |
| wifi_set_config | 3001 | `WifiSetConfigRequest { WifiConfig wifi_config = 1; }` | **write**; always refused without the official app's signature. Documented only to explain the refusal; the app doesn't send it |

`request` is a oneof: send exactly one of the fields above.

### WifiSetupRequest

| Field | No. | Type | Notes |
|---|---|---|---|
| skip | 1 | bool | Finish setup with defaults (untested) |
| network_name | 2 | string | Wi-Fi name (**verified**) |
| network_password | 3 | string | Wi-Fi password (**verified**: results in `auth_wpa2_wpa3` on both bands; limits untested) |
| bypass | 4 | bool | Bypass mode: Wi-Fi and router off (**verified**) |

There is no subnet field. The LAN stays `192.168.1.0/24` after setup; changing it needs `wifi_set_config`, which is refused (FINDINGS.md test 9), so only the official app can change it.

### Response (`SpaceX.API.Device.Response`)

| Field | No. | Type |
|---|---|---|
| id | 1 | uint64 |
| status | 2 | `Status { int32 code = 1; string message = 2; }` |
| api_version | 3 | uint64 (95 and 132 seen) |
| get_device_info | 1004 | `GetDeviceInfoResponse { DeviceInfo device_info = 1; }` |
| wifi_get_status | 3004 | `WifiGetStatusResponse` |
| wifi_setup | 3003 | empty message |
| wifi_set_config | 3001 | empty message |
| wifi_get_config | 3009 | `WifiGetConfigResponse { WifiConfig wifi_config = 1; }` |

### DeviceInfo

`id = 1` (e.g. `Router-0100…`, or `ut…` for the dish), `hardware_version = 2` (dish on a Mini: `mini1_prod1`; router: `v4`), `software_version = 3`, `country_code = 4`.

### WifiConfig (read fields used by the app)

| Field | No. | Type | Meaning |
|---|---|---|---|
| setup_complete | 7 | bool | false or absent = setup pending (the window this app uses) |
| bypass_mode | 31 | bool | |
| disable_2ghz / disable_5ghz / disable_5ghz_high | 64 / 65 / 66 | bool | per-band radio off (write refused, see findings) |
| apply_disable_2ghz / _5ghz / _5ghz_high | 1076 / 1077 / 1078 | bool | "apply" flag paired with each setting on writes |
| networks | 1100 | repeated `Network` | |

`Network`: `ipv4 = 1003` (e.g. `192.168.1.1/24`), `domain = 1011`, `basic_service_sets = 1007` (repeated `BasicServiceSet`), `vlan = 1014`.

`BasicServiceSet`: `bssid = 1003`, `ssid = 1005`, `band = 1011` (enum: 2 = 2.4 GHz, 5 = 5 GHz, 6 = 5 GHz high), `iface_name = 1017`, `disable = 1013`, `hidden = 1015`, and the auth oneof: `auth_open = 2001`, `auth_wpa2 = 2002`, `auth_wpa3 = 2003`, `auth_wpa2_wpa3 = 2004`. After a password setup, the app should confirm the auth field changed from `auth_open` (verified result: `auth_wpa2_wpa3`). `AuthWpa2` and `AuthWpa2Wpa3` carry `string password = 1`, which reads back masked as `•••••`; never compare it with the real password.

### WifiGetStatusResponse (selected)

`device_info = 3`, `device_state = 4` (`uptime_s`), `ipv4_wan_address = 1003`, `dhcp_servers = 1019`.

## Status codes seen (verified)

| Code | Message | When |
|---|---|---|
| 0 | | success |
| 7 PERMISSION_DENIED | `Permission denied` | any `wifi_set_config`, before or after setup, over Wi-Fi or Ethernet |
| 9 FAILED_PRECONDITION | `Wifi has already been set up` | `wifi_setup` on a configured router |
| 12 UNIMPLEMENTED | `unknown service grpc.reflection.v1.ServerReflection` | reflection v1 (v1alpha works) |

## Device states and how to tell them apart

| State | Router `192.168.1.1:9001` | `setup_complete` | Phone address on the Mini's link |
|---|---|---|---|
| Setup pending | answers | false/absent | `192.168.1.x` |
| Configured (router mode) | answers (at its own LAN address) | true | LAN subnet |
| Bypass | no answer | — | CGNAT `100.64.0.0/10` address and public IPv6, dish still at `192.168.100.1` |
| Rebooting | no answer | — | none, or link-local only |

## Timing (verified, one unit)

- Factory reset until the router answers again: about 35–110 s.
- `wifi_setup{bypass}` until Wi-Fi is gone and Ethernet has a CGNAT address: under 30 s. The response (status 0) arrived before the switch.
- `wifi_setup{network_name}` applies with a router restart (boot reason `CONFIG_UPDATE`).

## Server reflection

`grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo` works over gRPC-web on port 9001 for a single request message. It returns the full descriptor set. Useful for a debug screen that checks field numbers against the running firmware; not needed for normal operation.

## Test vectors

Byte-exact frames are in [tools/vectors/](../tools/vectors/). The `wifi_setup_bypass` and `wifi_setup_name` frames are the ones sent to the device on 2026-10-18 (with a different request `id`). `wifi_setup.response_ok` is a captured device response.

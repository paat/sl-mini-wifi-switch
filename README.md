# sl-mini-wifi-switch

An open-source Android app that switches a **Starlink Mini** between two field modes, without the official app and without an account sign-in:

| Mode | What the Mini does | When to use it |
|---|---|---|
| **Wi-Fi off** (bypass) | Built-in Wi-Fi and router are off. The Ethernet port hands one wired device an internet address straight from Starlink. | Semi-permanent spot where you want a small RF footprint. |
| **Secure Wi-Fi** | Built-in router on, Wi-Fi with your own name and password. | Quick stop: power bank in, phone or laptop on Wi-Fi, pack up. |

> **Status: pre-alpha, documentation only.** Bypass, Wi-Fi name and Wi-Fi password setup have each been verified by hand on one Starlink Mini (see [docs/FINDINGS.md](docs/FINDINGS.md)). No app code exists yet. [AGENTS.md](AGENTS.md) explains how a coding agent should take it from here.

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

What this app can't do, because the router refuses it: turn Wi-Fi off while keeping the router on, or change anything on an already configured Mini. Both were tested (see findings).

## Documentation

| File | Contents |
|---|---|
| [AGENTS.md](AGENTS.md) | Start here if you are a coding agent: goals, rules, where to begin |
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

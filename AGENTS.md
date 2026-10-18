# Guide for coding agents

You are taking over an Android project that so far exists only as documentation. Read this file, then [docs/UX.md](docs/UX.md), [docs/PROTOCOL.md](docs/PROTOCOL.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md). Start with milestone M0 in the roadmap.

## Goal

One Android app with one job: right after a Starlink Mini factory reset, finish its setup in either **bypass** (Wi-Fi off) or **secure Wi-Fi** (name and password), reached over the Mini's open setup Wi-Fi or over Ethernet. Plus a read-only status screen.

The users are **non-technical people in the field**. The app must be foolproof and guide every physical step with illustrations. [docs/UX.md](docs/UX.md) is binding: a screen that doesn't meet it isn't done.

## Non-goals

- Changing settings on a Mini that is already set up. The router refuses it without the official app's signed requests. Don't try to forge or replay them.
- Triggering a factory reset from the app. The reset stays a physical action on the Mini.
- Dish commands (stow, reboot, GPS, power save) and anything sent to the dish at `192.168.100.1`, except read-only status.
- Supporting other Starlink hardware before the Mini works. Keep the design open to it, but don't build for it.

## Hard rules

1. **Never send a write request to real hardware on your own.** Writes are: `wifi_setup` and `wifi_set_config` (see PROTOCOL.md for field numbers), and every other request in [docs/PROTOCOL.md](docs/PROTOCOL.md) marked write. Hardware tests are done by a human with the Mini in reach, following [docs/TESTING.md](docs/TESTING.md).
2. **Never send `factory_reset`, `dish_factory_reset`, or any request this project doesn't document.** Some exist on the device API; they are out of scope.
3. **Every write in the app needs an explicit user tap and a confirmation step** that says what will happen ("Wi-Fi will turn off. Getting it back needs a factory reset.").
4. **The protocol layer stays pure Kotlin with no Android imports**, so it runs in JVM unit tests against [tools/vectors/](tools/vectors/).
5. **Don't ship SpaceX's full protobuf schema.** The repo uses its own minimal subset ([proto/sl_mini_subset.proto](proto/sl_mini_subset.proto)) with only the field numbers this app needs. Field numbers are facts observed on the device; keep it that way.
6. **No network calls outside the local Starlink addresses** (`192.168.1.1`, `192.168.100.1`, and the router address found via DHCP). No analytics, no crash reporting services.
7. **Never log a Wi-Fi password.** Store it only with Android Keystore-backed encryption. Show it only where [docs/UX.md](docs/UX.md) says to (behind a show/hide toggle, on the result screen, and as a Wi-Fi QR code).
8. **No technical terms in the main interface** (bypass, subnet, gRPC, API, setup request). See the wording rules in [docs/UX.md](docs/UX.md).

## Conventions

- Kotlin, Jetpack Compose, Material 3, minSdk 29, targetSdk current.
- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`).
- Modules: `:protocol` (pure Kotlin/JVM) and `:app` (Android). See ARCHITECTURE.md.
- Tests: JUnit for `:protocol`; every request builder has a test that matches a vector file byte for byte.
- Keep user-facing text in `strings.xml`, plain English, written for someone in the field.
- Update [docs/FINDINGS.md](docs/FINDINGS.md) whenever a human hardware test confirms or disproves something, with date, firmware versions and the exact result.

## Definition of done for a milestone

- Builds with `./gradlew build` and passes `./gradlew test` on a clean checkout.
- New behaviour is covered by unit tests where it can be.
- New screens meet [docs/UX.md](docs/UX.md): plain wording, illustration for every physical step, no dead ends, field-condition sizes. Include phone screenshots (light and dark) in the PR.
- Docs updated: ROADMAP.md (tick the task), FINDINGS.md if hardware facts changed, PROTOCOL.md if messages changed.
- Anything that needs a hardware test is listed in the PR description with the exact steps from TESTING.md.

## Background

The protocol was worked out on 2026-10-18 by talking to a Starlink Mini over Ethernet from an Android phone (adb shell + curl), using the router's gRPC server reflection to read message definitions. The session log of that work is summarized in [docs/FINDINGS.md](docs/FINDINGS.md).

<!-- saas-startup-team:engineering-principles:start -->
## Engineering principles

Follow KISS, YAGNI, and DRY on every change:

- **KISS** — simplest design that fully ships the product need. Prefer boring, managed infrastructure one founder can run alone. Do not add enterprise machinery (service splits, self-operated brokers, SSO/SAML/SCIM hierarchies, multi-region HA, heavyweight observability) unless `Done` or a concrete security, legal, reliability, or operability requirement needs it.
- **YAGNI** — no speculative features, premature abstractions, unused config, or defensive branches for states that cannot happen. Do not expand scope because a future idea might need it.
- **DRY** — remove *meaningful* duplication (copy-pasted logic or restated rules that will drift). Do not invent frameworks for two similar lines, and keep intentional decorrelated variants (e.g. independent review legs) separate.

These apply to product code, tests, docs, prompts, and agent workflows. Completeness of authentication, validation, payments/data correctness, backups/recovery, and honest failure states is never cut for "simplicity."
<!-- saas-startup-team:engineering-principles:end -->

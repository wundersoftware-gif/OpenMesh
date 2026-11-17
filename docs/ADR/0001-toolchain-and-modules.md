# ADR 0001 — Toolchain and module boundaries

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** OpenMesh implementation team

## Context

`GOAL_PROMPT.md` requires an Android-first project that builds from Windows 11
with VS Code and PowerShell, without Android Studio, and without root. The
codebase must separate pure logic from Android so it can be tested on the JVM.
The initial directory proposal included modules that are not needed until later
phases (`storage`, `diagnostics`, `core/crypto`, `transport/tcp`,
`transport/tls`, `relay/forwarder`, `relay/session`).

The available machine had JDK 21 and JDK 25. AGP 9.0.1 and Gradle 9.5.1 were
available; Android SDK platform 36 was the newest installable platform (API 37
did not exist in the SDK repository).

## Decision

- Build with **JDK 21**, **Gradle 9.5.1**, **AGP 9.0.1**, **Kotlin 2.3.20**,
  **Compose BOM 2026.03.01**, `compileSdk`/`targetSdk` **36**, `minSdk` **29**.
- Java/Kotlin target **17** for Android and JVM modules.
- Use a smaller module set now, with placeholders for later phases:
  - `core:model`, `core:protocol`, `core:routing`
  - `gateway`
  - `vpn:android`, `vpn:tun2socks`
  - `transport`, `relay`, `discovery:ble`
  - `app`, `testclient`
- Keep `core` free of Android dependencies.
- Keep the native engine behind `vpn:tun2socks`; keep VPN lifecycle in
  `vpn:android`; keep the SOCKS/policy logic in `gateway` so it can be reused by
  a future remote listener and tested on the JVM.
- Do not create empty `storage/`/`diagnostics/` modules until a requirement
  exists. Status is currently in-memory and intentionally not persisted.
- Do not build native code from source; consume a pinned prebuilt AAR (ADR 0002).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Android Studio project only | Requirement says VS Code/CLI must be sufficient; a Gradle wrapper works for both |
| Monolithic `app` module | Cannot unit-test protocol/policy on the JVM; would mix Android and pure logic |
| JDK 25 as build JDK | AGP is not validated with it; reproducibility favors JDK 21 |
| `compileSdk 37` | Platform 37 was not available in the installed SDK repository |
| Create every proposed module now | Empty modules add build time and imply functionality that does not exist |

## Consequences

- `JAVA_HOME` must be set to JDK 21 for Gradle; documented in the README.
- Some proposed modules are absent or empty. Documentation must not imply that
  `transport`, `relay`, `core:routing` or `discovery:ble` are functional.
- Pure Kotlin tests run quickly on the host (35 tests today).
- A later P2 split of `gateway` into listener/outbound/DNS submodules can happen
  without changing the wire contract.

## References

- `GOAL_PROMPT.md` §1, §3, §4, §5, §17
- `gradle/libs.versions.toml`
- `README.md` §4–5

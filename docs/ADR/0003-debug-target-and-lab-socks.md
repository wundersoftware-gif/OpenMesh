# ADR 0003 — Debug target substitution and lab direct SOCKS

- **Status:** Accepted for P0/P1 only
- **Date:** 2026-11-17
- **Expiry:** before any public/release build
- **Deciders:** OpenMesh implementation team

## Context

P0/P1 must prove that Android routes only the allowlisted package into the TUN
and that `TUN → tun2socks → SOCKS5` works, without:

- modifying, automating or reverse-engineering WhatsApp;
- requiring a WhatsApp account or registration;
- requiring three physical phones for the first plumbing validation;
- exposing a SOCKS proxy on the network.

The development environment had an Android emulator and no physical device.
WhatsApp cannot be registered in the emulator without a phone number, and
automating it would violate the project rules.

There was also no remote gateway yet. Without one, a release build that captures
WhatsApp would have no destination for its traffic.

## Decision

1. **Debug builds target `com.openmesh.testclient`**, a small app in this
   repository. It generates DNS, HTTP and HTTPS traffic and a negative policy
   check. The UI explicitly says this is a debug substitution and not WhatsApp.
2. **Release builds target `com.whatsapp`** and have no gateway. They are
   capture-only: packets are drained and dropped. They must not be presented as
   usable for messaging.
3. **P1 lab mode uses a direct SOCKS egress** on loopback:
   - `LocalSocksFrontend` binds `127.0.0.1:0` with random per-session RFC 1929
     credentials;
   - `DirectSocketConnector` opens the destination socket directly;
   - `DestinationPolicy` blocks private/loopback/link-local/CGNAT/multicast;
   - the only private exception is the exact pair `10.0.2.2:8080`, supplied by
     the debug app for the emulator host server;
   - the UI labels the mode `LAB MODE · direct SOCKS` and keeps Route `NONE`
     and Internet `UNKNOWN`.
4. **Lab mode is unavailable in release** because only the debug UI sets
   `labDirectSocks`, and no release flow calls `LocalSocksFrontend`.
5. **The negative test uses a live listener** on `10.0.2.2:8081`, not a dead
   port. This distinguishes policy enforcement from a connection refusal.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Install WhatsApp in the emulator | Requires registration/phone number; automating it is out of scope and risky |
| Automate WhatsApp with accessibility/UIAutomator | Explicitly prohibited by `GOAL_PROMPT.md` |
| Use another real messaging app | Still third-party traffic and would not prove WhatsApp; a controlled test client is more reproducible |
| Point release builds at the test client | Would hide the real target and make release evidence meaningless |
| Use a public SOCKS proxy for P1 | Violates no-open-proxy and privacy requirements; unreliable and unsafe |
| Wait for three physical phones before any code | Blocks all progress; P0/P1 can be validated locally with a substitute, as long as it is labeled honestly |

## Consequences

- P0/P1 evidence applies to the **mechanism**, not to WhatsApp. This is stated
  in the README, UI and test plan.
- The release APK is intentionally not usable for messaging until P2. It is
  unsigned and should not be distributed.
- The debug allowlist must never leak into release. A release review must check
  `BuildConfig.OPENMESH_TARGET_PACKAGE`, `labDirectSocks` and
  `LAB_ALLOWLIST_ENTRY`.
- The test client is debug/lab code and should be excluded from a production
  distribution (or shipped only in a debug/lab flavor).
- Real WhatsApp validation still requires physical devices and is a P7 gate.

## Verification

- P1: test client DNS/HTTP/HTTPS succeeded; the decoy at 8081 was denied while
  running; host 8080 logged the request.
- P0: the same client failed closed when lab mode was off; both live host
  listeners received nothing and RX stayed 0.
- Chrome remained outside the VPN.
- Debug app targets `com.openmesh.testclient`; release build config targets
  `com.whatsapp`.

## References

- `GOAL_PROMPT.md` §1, §8, §13 (P0/P1), §16 (rules for agents)
- `app/build.gradle.kts` build types
- `app/src/main/kotlin/com/openmesh/app/MainActivity.kt`
- `vpn/android/src/main/kotlin/com/openmesh/vpn/android/LocalSocksFrontend.kt`
- `docs/evidence/`

# Changelog

All notable changes are recorded here. The project is pre-alpha; entries are
grouped by the phase they belong to.

## [0.1.0-p0] — 2026-11-17

First working repository. P0 and P1 are demonstrated in an Android emulator
with a substitute application. The mesh is not implemented.

### Added

- Gradle multiproject: `app`, `testclient`, `core:model`, `core:protocol`,
  `core:routing`, `gateway`, `vpn:android`, `vpn:tun2socks`, `transport`,
  `relay`, `discovery:ble`.
- Version catalog pinned to JDK 21, Gradle 9.5.1, AGP 9.0.1, Kotlin 2.3.20,
  Compose BOM 2026.03.01, SDK 36, `minSdk` 29.
- `core:model`: `MeshNode`, `NodeCapability`, `MeshRoute`, `GatewayInfo`,
  `VpnStatus`/`VpnPhase`/`LinkState`/`RouteState`/`InternetState`, typed
  `OpenMeshException`.
- `core:protocol`: OMSH v1 `MeshFrame`, `FrameType`, strict bounded
  `FrameCodec` with stream reads.
- `gateway`: restricted SOCKS5 server (CONNECT + DNS-only UDP ASSOCIATE),
  RFC 1929 auth, `DestinationPolicy`, `DirectSocketConnector`,
  `Socks5Client`, `UdpDnsRelay`, `GatewayMetrics`, metadata-only
  `GatewayAudit`.
- `vpn:tun2socks`: pinned hev-socks5-tunnel 2.20.0 AAR, YAML config generator,
  JNI lifecycle, stats and log tail.
- `vpn:android`: `OpenMeshVpnService` with foreground notification, TUN
  `10.111.0.2/32` + `fd00:111::2/128`, default routes, DNS inside the tunnel,
  per-app allowlist, `onRevoke`, clean stop, socket protection.
- `CaptureOnlyEngine` (P0 drain-and-drop).
- `LocalSocksFrontend` (P1 loopback SOCKS with random per-session credentials).
- Compose UI with independent Link / Route / Internet states, honest phase
  explanations, target card, debug lab toggle, counters, details, errors and
  emergency stop.
- `testclient` lab app: DNS, HTTP to the host, HTTPS to a public site, and an
  expected-deny check against a live decoy port.
- `tools/test-server.mjs`: health, bytes, slow and echo endpoints.
- ADRs 0001–0005; README, architecture, protocol, security, test plan,
  implementation status, changelog, third-party notices.
- Emulator evidence: screenshots, transcripts and host server logs under
  `docs/evidence/`.

### Changed / fixed

- Fixed missing `SocketProtector` import and `VpnService.protect` call syntax.
- Fixed a negative `active` counter in `GatewayMetrics.closed()`.
- Removed an unused `readFully` helper from `Socks5Client`.
- Reset VPN counters on stop and on service teardown.
- Replaced the dead-port negative check with a live decoy listener on
  `10.0.2.2:8081`, so a policy failure would produce HTTP 200 instead of a
  connection refusal.
- TUN establish failure is mapped to a typed error instead of a generic crash.
- SOCKS tests wait for asynchronous metrics before asserting.

### Tested

- 35 JVM tests pass: 17 frame codec, 8 destination policy, 5 SOCKS5 server, 5
  tun2socks config.
- P1 emulator: DNS, HTTP 200 (206 B), HTTPS 200 (577 B, TLS 1.3) through
  TUN → tun2socks → loopback SOCKS; counters and server logs agree.
- Private destination `10.0.2.2:8081` denied in 96 ms while a live listener was
  running; `10.0.2.2:8080` allowed.
- P0 emulator: DNS failure, TCP timeouts from source `10.111.0.2`, no host
  server requests, RX = 0.
- Chrome loads `example.org` while the VPN is active (outside the allowlist).
- Stop removes `tun0`, resets counters and states, removes the notification.
- Debug and release builds succeed; release is unsigned.

### Not implemented

- Remote gateway, TLS 1.3 node authentication, end-to-end inner channel.
- Relay, BLE discovery, Wi-Fi Direct, dynamic routing.
- Real WhatsApp validation, physical arm64 validation, attachments,
  notifications, UDP/QUIC behaviour.
- Node identity, quotas, consent/revocation UI, replay protection, fuzzing.
- Persistence, CI, release signing, OpenMesh code license.

### Security

- Fail-closed capture: no silent direct fallback.
- Loopback-only SOCKS; no public listener; random per-session credentials.
- Private/loopback/link-local/CGNAT/multicast destinations denied; exact lab
  allowlist only; anti-DNS-rebinding.
- Generic UDP and BIND rejected; DNS-only UDP with fixed upstreams.
- No custom cryptography, no analytics, no payload/DNS-name logging.
- P0/P1 are **not** hardened for hostile networks. See `docs/SECURITY.md`.

# Changelog

All notable changes are recorded here. The project is pre-alpha; entries are
grouped by the phase they belong to.

## [0.2.0-p2] — 2026-11-17

P2: a **direct gateway over authenticated TLS 1.3**. One gateway hop (A → C →
Internet), no relay. Validated emulator-to-emulator with the lab traffic
client. The mesh, BLE, Wi-Fi Direct and WhatsApp runs are still not
implemented.

### Added

- `transport` module: TLS 1.3 client/server contexts, a self-signed EC P-256
  node identity persisted as PKCS#12, SHA-256 `Fingerprint` handling and
  `PinnedFingerprintTrustManager`. No custom cryptography: the platform TLS
  stack does the work, Bouncy Castle only builds the certificate.
- `gateway`: `GatewayServer` — a TLS 1.3 listener that runs the existing
  restricted SOCKS5 server on the decrypted stream, with RFC 1929
  authentication and per-connection TLS.
- `gateway/client`: `RemoteGatewayConnector` — pinned TLS client socket with
  `Socks5Client` inside, one cached session per destination.
- `gateway/socks5`: `DnsResolver` interface plus `UdpDnsResolver` (existing
  behaviour) and `SocksDnsResolver` (DNS-over-TCP through the gateway to
  `1.1.1.1:53`/`9.9.9.9:53`).
- `vpn:android`: `SocksFrontend` abstraction, `RemoteSocksFrontend` (P2 path
  with injected DNS resolver, connector and TLS settings), `RemoteGatewayProbe`
  (honest route/Internet probe every 30 s), gateway fields in
  `VpnStartOptions`/`VpnStatus`/preferences, remote monitor and notifications.
- `app`: `GatewayService` (foreground service with notification and stop
  action), `GatewayPreferences`, `GatewayIdentityStore`, `GatewayState`, gateway
  card in the UI (start/stop, port, fingerprint, token, counters), mode
  selector with the three modes (direct gateway P2, lab direct P1 debug-only,
  capture only P0) and a warning explaining what P2 is and is not.
- `app/src/debug`: `DebugControlReceiver` (debug source set only) for
  repeatable adb-driven evidence runs: start/stop the gateway and the VPN,
  dump status to logcat.
- `testclient`: logs every run to logcat tag `OpenMeshLab` so evidence can be
  captured without screenshots.
- ADR 0006 (P2 as a direct TLS gateway), P2 evidence transcript and gateway
  screenshot under `docs/evidence/`.

### Changed

- `Socks5Server` accepts an external server-socket factory, an injected DNS
  resolver, a connection initializer and buffered stream piping, so the same
  tested server serves loopback (P1) and TLS (P2) traffic.
- `Socks5Client` supports deadlock-free duplex piping after the CONNECT reply.
- `UdpDnsRelay` routes through the `DnsResolver` abstraction.
- `VpnControl` publishes gateway-aware status; `VpnStatus` carries
  `remoteGateway`, `gatewayId`, `hops` and probe detail.
- `testclient` version bumped to `0.2.0-p2` (versionCode 2).

### Security-relevant

- Gateway certificate pinning by SHA-256 fingerprint; a mismatch aborts the TLS
  handshake and the route stays `NONE`.
- Per-gateway RFC 1929 token sent inside TLS; a wrong token is rejected before
  any SOCKS request is served.
- Gateway destination policy blocks private/loopback/link-local/CGNAT/
  multicast/reserved ranges so the gateway cannot be used to reach its own LAN.
- Fail-closed verified three ways: gateway stopped, wrong token, wrong pin.
- Known gaps documented, not hidden: bearer token in SharedPreferences (not
  Keystore), PKCS#12 key in app-private storage, no mutual TLS, no quotas, no
  revocation, gateway listener on `0.0.0.0:8443` untested against a hostile
  network.

### Tested

- 45 JVM tests pass (17 frame codec, 8 destination policy, 5 SOCKS5 server,
  5 tun2socks config, 5 TLS loopback, 5 TLS gateway: pinned handshake, wrong
  fingerprint, wrong token, private destination denied, DNS-over-TCP).
- Emulator pair (`emulator-5554` gateway, `emulator-5556` client):
  DNS 1146 ms, HTTP 200 (206 B), HTTPS 200 (577 B,
  `TLS_AES_128_GCM_SHA256`), decoy on `10.0.2.2:8081` denied, client
  `phase=TUNNEL_READY, link=UP, route=SELECTED, internet=VERIFIED,
  gatewayId=10.0.2.2:8443, hops=1`, gateway `accepted=6, denied=7`.
- Fail-closed: gateway stopped, wrong token, wrong fingerprint — all
  `NO_ROUTE` with a visible reason and no leaked traffic.
- Gateway identity persists across a restart (same fingerprint, no re-pinning).
- **Physical arm64 phone (2026-11-18), Redmi 13C, HyperOS 2.0 / Android 15:**
  - Phone as client against the emulator gateway: DNS 303 ms, HTTP 200 (206 B,
    105 ms), HTTPS 200 (577 B, `TLS_AES_128_GCM_SHA256`, 553 ms), decoy denied,
    `Route=SELECTED`, `Internet=VERIFIED`, `hops=1`.
  - Same phone with its Wi-Fi uplink up and the gateway stopped: `NO_ROUTE`,
    every fetch fails — fail-closed on hardware, not only in the emulator.
  - Phone as gateway, reached by the emulator over real Wi-Fi
    (`192.168.1.58:8443`, no adb forwarding): DNS 310 ms, HTTPS 200 (577 B,
    1265 ms), decoy denied, gateway `accepted=7, denied=4`, identity persisted
    across a restart.
- `:app:assembleDebug` and `:testclient:assembleDebug` succeed.

### Not implemented / not validated

- No relay (B), no multi-hop, no BLE, no Wi-Fi Direct, no routing.
- Not run against WhatsApp: no WhatsApp account exists on the test devices.
- Physical runs use **one real phone at a time** (phone client + emulator
  gateway, or phone gateway + emulator client over real Wi-Fi). Two physical
  phones in the same run remain pending.
- The phone-as-client run used `adb reverse` as the lab transport; the
  phone-as-gateway run used real Wi-Fi with no forwarding. A two-phone LAN run
  is still required.
- No mutual TLS, no Android Keystore storage for token/key, no quotas, no
  revocation UI, no fuzzing of the TLS/SOCKS surface, no IPv6 end-to-end.
- No release signing; the release APK remains unsigned and is not distributed.
- No OMSH frame is carried on any wire yet; P2 carries SOCKS5 inside TLS.

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

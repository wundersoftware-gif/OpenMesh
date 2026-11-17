# OpenMesh Implementation Status

Last updated: **2026-11-17**
Version: `0.2.0-p2-debug` (debug) / `0.2.0-p2` (release, unsigned)

This file is the authoritative phase status. If another document, a commit
message or a UI label disagrees with this file, this file wins.

---

## 1. Bottom line

- **P0:** implemented and validated in an emulator with a substitute app.
- **P1:** implemented and validated in an emulator with a substitute app and a
  direct lab SOCKS egress.
- **P2 direct gateway:** implemented and validated **emulator-to-emulator**
  (A → C → Internet over authenticated TLS 1.3). There is no relay B yet.
- **P3–P6:** not implemented.
- **Real WhatsApp over A → B → C:** not demonstrated. No physical device was
  available, WhatsApp is not installed in the emulator, and the three-phone
  mesh does not exist.
- **The full mesh described in `GOAL_PROMPT.md` is not present.** Do not
  present this repository as a working multi-hop mesh VPN.

P2 is a single gateway hop, not a mesh: the client connects directly to one
gateway, the gateway exits to the Internet, and there is no forwarding between
clients. The phase gate for BLE, Wi-Fi Direct and multi-hop routing remains
closed until P0/P1/P2 are validated on physical hardware with WhatsApp.

---

## 2. Phase status

| Phase | Status | What exists | What is missing / next |
|---|---|---|---|
| **P0** - Gradle + VPN skeleton | **Partial pass (lab)** | Multiproject Gradle; Compose UI; VPN consent flow; foreground `VpnService`; TUN IPv4/IPv6; one-package allowlist; capture-only drain/drop; status, counters, errors, emergency stop; Chrome-outside test | Physical arm64 run; WhatsApp target run; manual consent/denial and `onRevoke` UI tests; release install test |
| **P1** - TUN + tun2socks + SOCKS5 | **Partial pass (lab)** | hev-socks5-tunnel 2.20.0 pinned; JNI wrapper; loopback SOCKS5 with random auth; TCP CONNECT; DNS-only UDP; destination policy; socket protection; no-loop evidence; fail-closed evidence | WhatsApp traffic; IPv6 end-to-end; load/soak/fuzz tests; physical arm64 |
| **P2** - Direct TLS gateway | **Partial pass (lab, A → C only)** | Self-signed EC gateway identity persisted as PKCS#12; TLS 1.3 listener; SHA-256 certificate pinning; RFC 1929 token inside TLS; per-destination TLS; DNS-over-TCP through the gateway; remote frontend; honest route/Internet probe; gateway UI and counters; wrong-pin and wrong-token rejection; fail-closed when the gateway is absent | Mutual TLS/client certificates; Android Keystore for the token; physical phone-to-phone LAN run; WhatsApp target; quotas and revocation; graceful drain |
| **P3** - Relay / multi-hop | **Not implemented** | `relay` module placeholder only | Frame forwarding, stream IDs, credit, hop limits; keep relays unable to read destinations or payloads |
| **P4** - BLE discovery | **Not implemented** | `discovery:ble` module placeholder only | Advertising, scanning, permissions, authenticated handshake, lifecycle |
| **P5** - Wi-Fi Direct | **Not implemented** | None | Group negotiation, multi-vendor testing, concurrency with Internet |
| **P6** - Dynamic routing | **Not implemented** | `core:routing` placeholder only | Neighbour table, authenticated announcements, cost, expiry, loops, failover |
| **P7** - Real WhatsApp | **Not started / blocked** | None | Three physical phones, accounts, offline client, measurements, feature gaps |

Legend: **Partial pass (lab)** means the mechanism works with the lab
substitute app in the emulator; it is not a pass for WhatsApp or physical
hardware.

---

## 3. What P2 actually is

```text
client phone A                                     gateway phone C
┌──────────────────────────┐   TCP + TLS 1.3    ┌──────────────────────────┐
│ allowlisted app          │   pin + token      │ GatewayService           │
│   ↓ TUN                  │ ─────────────────► │  TLS listener            │
│ hev-socks5-tunnel        │   SOCKS5 CONNECT   │  RFC 1929 authentication │
│   ↓ loopback SOCKS5      │   DNS-over-TCP     │  Socks5Server            │
│ RemoteSocksFrontend      │                    │  DestinationPolicy       │
│ RemoteGatewayConnector   │                    │  direct sockets          │
└──────────────────────────┘                    └────────────┬─────────────┘
                                                             ▼
                                                          Internet
```

- The gateway proves its identity with a self-signed EC P-256 certificate. The
  client pins the SHA-256 fingerprint out of band; a mismatch aborts the TLS
  handshake. Hostname verification is intentionally replaced by the pin.
- The per-gateway token is sent with RFC 1929 inside the TLS session. A wrong
  token is rejected before any SOCKS request is served.
- Each destination gets its own TCP+TLS connection. There is no multiplexing.
- DNS is sent as DNS-over-TCP to `1.1.1.1:53`/`9.9.9.9:53` **through the
  gateway**; the client never sends DNS on the local network.
- Route and Internet are not configuration labels: the service runs a real
  probe (`example.com:443`, nested TLS 1.3, HTTP HEAD 2xx/3xx) and only then
  publishes `Route=SELECTED` and `Internet=VERIFIED`.
- If the gateway is missing, untrusted or unauthenticated, the TUN stays up
  and `Route=NONE`/`Internet=FAILED` are shown; captured traffic fails closed.

`docs/evidence/p2-direct-gateway-2026-11-17.txt` contains the positive path,
the gateway-down run, the wrong-token run and the wrong-pin run.

---

## 4. Deliverables from `GOAL_PROMPT.md` §17

| # | Deliverable | Status |
|---|---|---|
| 1 | Gradle multiproject repository | Done |
| 2 | Toolchain and compatible versions verified | Done (JDK 21, Gradle 9.5.1, AGP 9.0.1, SDK 36) |
| 3 | Investigate tun2socks and select a maintained option | Done - ADR 0002; hev 2.20.0, MIT, AAR pinned |
| 4 | Minimal Android application | Done |
| 5 | Request VPN authorization | Done; system dialog flow implemented, automated lab used appops |
| 6 | Create TUN | Done and observed (`tun0`, `10.111.0.2/32`, `fd00:111::2/128`) |
| 7 | Allowlist `com.whatsapp` | Implemented in release config; **not run** (debug uses test client) |
| 8 | Show VPN state | Done, with independent Link / Route / Internet |
| 9 | Tests proving other apps stay outside | Chrome test done in emulator |
| 10 | Document limitations | Done - README, this file, TEST_PLAN, SECURITY |
| 11 | Local tun2socks → SOCKS5 demonstration | Done with lab client, not WhatsApp |
| 12 | Remote authenticated gateway | Done in the emulator (A → C), not on physical phones |
| 13 | P2 UI and gateway lifecycle | Done; gateway foreground service, pin/token display, counters |
| 14 | Fail-closed with a missing/untrusted gateway | Done in the emulator |

---

## 5. Module status

| Module | Production code | Tests | Notes |
|---|---|---|---|
| `app` | Yes | No instrumentation tests | Compose UI, three client modes, gateway card and service, debug-only lab receiver |
| `testclient` | Lab only | No | Debug/demo traffic generator; not part of a release |
| `core:model` | Yes | No dedicated tests | Models are simple; `MeshNode` validates IDs and battery |
| `core:protocol` | Yes | 17 | Strict bounded OMSH codec; not used by the P2 stream path |
| `core:routing` | No | No | Placeholder |
| `gateway` | Yes | 18 | SOCKS5 server, policy, DNS resolvers, TLS gateway, pinned client connector, metrics |
| `vpn:tun2socks` | Yes | 5 | JNI wrapper + config |
| `vpn:android` | Yes | No unit tests | Service/TUN/capture, lab and remote frontends, probe; exercised on emulator |
| `transport` | Yes | 5 | TLS 1.3 contexts, identity, fingerprint pinning, loopback tests |
| `relay` | No | No | Placeholder |
| `discovery:ble` | No | No | Placeholder |

Total automated tests: **45**, all passing.

---

## 6. Requirements checklist (honest)

### VPN and capture

- [x] Official `VpnService`, no root.
- [x] Foreground service with notification and `specialUse` type.
- [x] Per-app allowlist via `addAllowedApplication`.
- [x] Only the target package is captured (verified with substitute + Chrome).
- [x] Default IPv4 and IPv6 routes.
- [x] DNS servers inside the tunnel.
- [x] Own sockets excluded (not in allowlist) and `protect()` used.
- [x] `onRevoke()` and safe teardown.
- [x] Visible error states and emergency stop.
- [ ] WhatsApp package captured on a real device.
- [ ] Manual consent dialog and denial path tested.
- [ ] Revocation tested on a device.

### Data path

- [x] tun2socks integrated and running (`tun2socks` engine, counters move).
- [x] TCP through local SOCKS5.
- [x] DNS through the tunnel and a restricted relay.
- [x] No loop observed in the lab path.
- [x] Fail-closed when the lab path is off.
- [x] Strict destination policy with anti-rebinding.
- [x] BIND and generic UDP rejected; only DNS is allowed.
- [x] Remote gateway path (P2) over authenticated TLS 1.3 in the emulator.
- [x] Certificate pinning and per-gateway token authentication.
- [x] DNS-over-TCP through the gateway on the P2 client.
- [x] Wrong pin, wrong token and missing gateway fail closed.
- [ ] Relay and multi-hop (P3).
- [ ] IPv6 end-to-end.
- [ ] Load, soak, fuzz and fault-injection tests.
- [ ] Mutual TLS / client certificates.

### Mesh and product

- [ ] Relay node B (P3) and three-phone A → B → C.
- [ ] End-to-end confidentiality through relays.
- [ ] BLE discovery.
- [ ] Wi-Fi Direct.
- [ ] Dynamic routing, failover, loop detection.
- [ ] Gateway consent list, quotas, revocation.
- [ ] Android Keystore for the gateway key and token.
- [ ] Real WhatsApp messaging, attachments, notifications.
- [ ] Battery/latency/throughput measurements on hardware.
- [ ] Physical arm64 matrix.

### Documentation and process

- [x] README, architecture, protocol, security, test plan.
- [x] ADRs for toolchain, tun2socks, lab substitution, fail-closed DNS/IPv6/UDP, framing and the P2 direct gateway.
- [x] Changelog.
- [x] Third-party notices and pinned hashes.
- [x] Reproducible emulator evidence with screenshots and server logs.
- [ ] License for OpenMesh's own code.
- [ ] CI pipeline (build + tests + lint) and artifact retention.
- [ ] Release signing and distribution process.

---

## 7. Known defects and limitations

1. `Socks5Server.close()` uses `shutdownNow()` and does not drain in-flight
   connections gracefully. Acceptable for a lab, not for a production gateway.
2. The gateway listener binds `0.0.0.0:8443`; it is protected by TLS 1.3 and
   the token but has not been fuzzed or exposed to a hostile network.
3. The gateway token and the gateway TLS private key are stored as ordinary
   app-private data. The token is in SharedPreferences; the key is in a
   PKCS#12 file. Android Keystore migration is not done.
4. There is no mutual TLS: the client verifies the gateway, but the gateway
   authenticates the client only with a bearer token. Token rotation is manual
   (regenerate on the gateway, re-enter on the client).
5. The P2 client opens one TCP+TLS connection per destination. There is no
   multiplexing, connection reuse or 0-RTT; latency and handshake overhead are
   higher than a pooled transport.
6. The gateway probe runs every 30 s and fetches `example.com` through the
   gateway. It is honest but consumes a small amount of data.
7. The debug build exports `DebugControlReceiver` so adb can start the services
   without UI taps, and the debug gateway policy allows exactly
   `10.0.2.2:8080`. Neither exists in a release build.
8. No physical-device evidence exists. All P2 evidence is two x86_64
   emulators on one host with an `adb forward`.
9. The release APK is unsigned in the Gradle project and targets
   `com.whatsapp`; it has not been run against WhatsApp. Do not treat it as a
   validated messaging product.
10. The OMSH frame codec is still not used by the P2 transport; P2 carries
    SOCKS5 directly inside TLS. The codec remains the contract for the relay
    phase.
11. The native tun2socks AAR is prebuilt; it is pinned by hash but not rebuilt
    from source in this repository.

---

## 8. Next steps, in order

1. **Physical P2 validation with two phones.**
   - Same Wi-Fi/LAN, no adb forward.
   - Start the gateway on phone C, copy the fingerprint and token out of band,
     start the client on phone A with the debug target.
   - Record DNS, HTTP 8080, HTTPS 200/577 B, the deny on 8081, and gateway
     counters.
   - Stop the gateway and repeat the fail-closed run.
2. **Manual consent and revoke tests** on a phone: system dialog, denial,
   `onRevoke` from Android settings.
3. **WhatsApp P2 run.** Install the WhatsApp-targeting release on a phone with
   a test account, confirm capture and P2 egress, then measure text send/receive
   and notifications. Do not modify or automate WhatsApp.
4. **P2 hardening.** Mutual TLS or client certificates, Android Keystore for
   the token and key, graceful drain, connection pooling, quotas, revocation
   UI, fuzzing, IPv6 egress, and a release signing process.
5. **Only after physical P2 + WhatsApp validation:** P3 relay, then BLE/Wi-Fi
   Direct and routing, each behind its own acceptance gate.
6. **P7 acceptance.** Three physical phones, client genuinely offline except
   for the mesh link, reproducible message evidence.

Do not reorder these steps. The project's credibility depends on not claiming a
later phase before the earlier one is reproducible on hardware.

---

## 9. Evidence index

| Evidence | Location |
|---|---|
| P0 fail-closed transcript | `docs/evidence/p0-failclosed-2026-11-17.txt` |
| P0 screenshots | `docs/evidence/p0-app-failclosed.png`, `p0-testclient-failclosed.png` |
| P1 lab transcript | `docs/evidence/p1-testclient-2026-11-17.txt` |
| P1 screenshots | `docs/evidence/p1-app-counters.png`, `p1-testclient-run.png` |
| Chrome outside VPN | `docs/evidence/p1-chrome-outside-vpn.png` |
| P2 direct-gateway transcript | `docs/evidence/p2-direct-gateway-2026-11-17.txt` |
| P2 app screenshot | `docs/evidence/p2-app-gateway-running.png` |
| Host servers | `docs/evidence/host-test-server-8080.log`, `host-test-server-8081.log` |
| Release notes and artifact hashes | `docs/evidence/release-notes-v0.2.0-p2.md` |
| Published lab release | <https://github.com/wundersoftware-gif/OpenMesh/releases/tag/v0.2.0-p2> (pre-release, debug APK + lab client) |
| Test matrix and methods | `docs/TEST_PLAN.md` |

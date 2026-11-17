# OpenMesh Implementation Status

Last updated: **2026-11-17**
Version: `0.1.0-p0-debug` (debug) / `0.1.0-p0` (release)

This file is the authoritative phase status. If another document, a commit
message or a UI label disagrees with this file, this file wins.

---

## 1. Bottom line

- **P0:** implemented and validated in an emulator with a substitute app.
- **P1:** implemented and validated in an emulator with a substitute app and a
  direct lab SOCKS egress.
- **P2–P7:** not implemented.
- **Real WhatsApp over A → B → C:** not demonstrated. No physical device was
  available and the emulator has no WhatsApp account.
- **The mesh features described in `GOAL_PROMPT.md` are not present.** Do not
  present this repository as a working mesh VPN.

The phase gate in `GOAL_PROMPT.md` says not to start BLE, Wi-Fi Direct or
multi-hop routing until P0/P1 are validated. Since P0/P1 have not been validated
against WhatsApp on physical hardware, **P2 was intentionally not started**.
Continuing would violate the project's own gate.

---

## 2. Phase status

| Phase | Status | What exists | What is missing / next |
|---|---|---|---|
| **P0** — Gradle + VPN skeleton | **Partial pass (lab)** | Multiproject Gradle; Compose UI; VPN consent flow; foreground `VpnService`; TUN IPv4/IPv6; one-package allowlist; capture-only drain/drop; status, counters, errors, emergency stop; Chrome-outside test | Physical arm64 run; WhatsApp target run; system consent/denial and `onRevoke` UI tests; release install test |
| **P1** — TUN + tun2socks + SOCKS5 | **Partial pass (lab)** | hev-socks5-tunnel 2.20.0 pinned; JNI wrapper; loopback SOCKS5 with random auth; TCP CONNECT; DNS-only UDP; destination policy; socket protection; no-loop lab evidence; fail-closed evidence | WhatsApp traffic; remote gateway (P2); IPv6 end-to-end; load/soak/fuzz tests; physical arm64 |
| **P2** — Remote Android gateway | **Not implemented** | Reusable restricted SOCKS5 server, policy and metrics; protocol codec | Node identity, TLS 1.3 mutual auth, gateway listener, end-to-end channel, consent/revocation |
| **P3** — Relay | **Not implemented** | `relay` module placeholder only | Frame forwarding, stream IDs end to end, credit, hop limits, confidentiality proof |
| **P4** — BLE discovery | **Not implemented** | `discovery:ble` module placeholder only | Advertising, scanning, permissions, authenticated handshake, lifecycle |
| **P5** — Wi-Fi Direct | **Not implemented** | None | Group negotiation, multi-vendor testing, concurrency with Internet |
| **P6** — Dynamic routing | **Not implemented** | `core:routing` placeholder only | Neighbour table, authenticated announcements, cost, expiry, loops, failover |
| **P7** — Real WhatsApp | **Not started / blocked** | None | Three physical phones, accounts, offline client, measurements, feature gaps |

Legend: **Partial pass (lab)** means the mechanism works with the lab
substitute app in the emulator; it is not a pass for WhatsApp or physical
hardware.

---

## 3. Deliverables from `GOAL_PROMPT.md` §17

| # | Deliverable | Status |
|---|---|---|
| 1 | Gradle multiproject repository | Done |
| 2 | Toolchain and compatible versions verified | Done (JDK 21, Gradle 9.5.1, AGP 9.0.1, SDK 36) |
| 3 | Investigate tun2socks and select a maintained option | Done — ADR 0002; hev 2.20.0, MIT, AAR pinned |
| 4 | Minimal Android application | Done |
| 5 | Request VPN authorization | Done; system dialog flow implemented, automated lab used appops |
| 6 | Create TUN | Done and observed (`tun0`, `10.111.0.2/32`, `fd00:111::2/128`) |
| 7 | Allowlist `com.whatsapp` | Implemented in release config; **not run** (debug uses test client) |
| 8 | Show VPN state | Done, with independent Link / Route / Internet |
| 9 | Tests proving other apps stay outside | Chrome test done in emulator |
| 10 | Document limitations | Done — README, this file, TEST_PLAN, SECURITY |
| 11 | Local tun2socks → SOCKS5 demonstration | Done with lab client, not WhatsApp |

---

## 4. Module status

| Module | Production code | Tests | Notes |
|---|---|---|---|
| `app` | Yes | No instrumentation tests | Compose UI, permission launcher, target check, debug toggle |
| `testclient` | Debug/lab only | No | Not part of a release |
| `core:model` | Yes | No dedicated tests | Models are simple; `MeshNode` validates IDs and battery |
| `core:protocol` | Yes | 17 | Strict bounded codec |
| `core:routing` | No | No | Placeholder |
| `gateway` | Yes | 13 | SOCKS5 server, policy, direct connector, DNS relay, metrics |
| `vpn:tun2socks` | Yes | 5 | JNI wrapper + config |
| `vpn:android` | Yes | No unit tests | Service/TUN/capture/lab frontend; exercised on emulator |
| `transport` | No | No | Placeholder; Bouncy Castle declared |
| `relay` | No | No | Placeholder |
| `discovery:ble` | No | No | Placeholder |

Total automated tests: **35**, all passing.

---

## 5. Requirements checklist (honest)

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
- [ ] Remote gateway path (P2).
- [ ] Relay and multi-hop (P3).
- [ ] IPv6 end-to-end.
- [ ] Load, soak, fuzz and fault-injection tests.

### Mesh and product

- [ ] Node identity and mutual TLS.
- [ ] End-to-end confidentiality through relays.
- [ ] BLE discovery.
- [ ] Wi-Fi Direct.
- [ ] Dynamic routing, failover, loop detection.
- [ ] Gateway consent, quotas, revocation.
- [ ] Real WhatsApp messaging, attachments, notifications.
- [ ] Battery/latency/throughput measurements on hardware.
- [ ] Physical arm64 matrix.

### Documentation and process

- [x] README, architecture, protocol, security, test plan.
- [x] ADRs for toolchain, tun2socks, lab substitution, fail-closed DNS/IPv6/UDP and framing.
- [x] Changelog.
- [x] Third-party notices and pinned hashes.
- [x] Reproducible emulator evidence with screenshots and server logs.
- [ ] License for OpenMesh's own code.
- [ ] CI pipeline (build + tests + lint) and artifact retention.
- [ ] Release signing and distribution process.

---

## 6. Known defects and limitations

1. `Socks5Server.close()` uses `shutdownNow()` and does not drain in-flight
   connections gracefully. Acceptable for a lab frontend, not for a production
   gateway.
2. The lab SOCKS path is direct and does not hide destinations from the local
   network. It is debug-only.
3. `GatewayAudit` is wired as a no-op in the lab; metrics are aggregate only.
4. The frame codec has no transport, handshake, flow control or replay layer.
5. `VpnStatus.route` and `.internet` can never become `SELECTED`/`VERIFIED` in
   this build. This is intentional honesty, not a completed feature.
6. The release build is unsigned and capture-only.
7. The native tun2socks AAR is prebuilt; it is pinned by hash but not rebuilt
   from source in this repository.
8. `transport` declares Bouncy Castle but contains no code. Review the
   dependency and remove it if unused when P2 starts.

---

## 7. Next steps, in order

1. **Physical P0/P1 validation.**
   - Obtain at least one arm64 Android phone (three for the final MVP).
   - Install the release build, confirm it refuses to start when WhatsApp is
     missing and that it captures only `com.whatsapp`.
   - Run the P0 fail-closed probe and the P1 lab probe on the phone. Record
     `tun0`, counters, server logs and screenshots.
2. **Manual permission and revoke tests.** Exercise the system consent dialog,
   denial, and `onRevoke` from Android settings.
3. **P1 hardening.** Fuzz `FrameCodec` and the SOCKS parser; add a bounded
   drain to `Socks5Server`; test malformed DNS datagrams; test IPv6 end to end.
4. **Only then start P2.** Implement node identity (Android Keystore), TLS 1.3
   mutual authentication, a gateway listener bound to authenticated peers, and
   an end-to-end inner channel. Keep the relay unable to parse payloads.
5. **P3 relay.** Add opaque forwarding, per-stream credit and hop limits; prove
   with a packet/log inspection that B sees only ciphertext.
6. **P4/P5/P6.** BLE, Wi-Fi Direct and routing, each behind its own acceptance
   gate and physical test matrix.
7. **P7.** Real WhatsApp messages, attachments and notifications on three
   phones, with the client genuinely offline except for the mesh link.

Do not reorder steps 1–4. The project's credibility depends on not claiming a
later phase before the earlier one is reproducible on hardware.

---

## 8. Evidence index

| Evidence | Location |
|---|---|
| P0 fail-closed transcript | `docs/evidence/p0-failclosed-2026-11-17.txt` |
| P0 screenshots | `docs/evidence/p0-app-failclosed.png`, `p0-testclient-failclosed.png` |
| P1 lab transcript | `docs/evidence/p1-testclient-2026-11-17.txt` |
| P1 screenshots | `docs/evidence/p1-app-counters.png`, `p1-testclient-run.png` |
| Chrome outside VPN | `docs/evidence/p1-chrome-outside-vpn.png` |
| Host servers | `docs/evidence/host-test-server-8080.log`, `host-test-server-8081.log` |
| Test matrix and methods | `docs/TEST_PLAN.md` |

# OpenMesh Architecture

This document describes the **implemented** architecture first, then the
designed-but-not-implemented mesh architecture. Anything in the second part is
a plan, not a claim.

Companion documents: [`PROTOCOL.md`](PROTOCOL.md), [`SECURITY.md`](SECURITY.md),
[`TEST_PLAN.md`](TEST_PLAN.md), [`IMPLEMENTATION_STATUS.md`](../IMPLEMENTATION_STATUS.md).

---

## 1. System context

OpenMesh is an Android application that uses the official `VpnService` API to
capture the traffic of exactly one allowlisted package and to choose what
happens to it:

- **P0 capture-only** — drain the TUN and drop every packet. This is the
  fail-closed default when no gateway data path exists.
- **P1 lab direct SOCKS** — send packets through a userspace TCP/IP stack
  (`hev-socks5-tunnel`) to a loopback SOCKS5 server, which opens direct
  destination sockets. This exists only to prove the VPN plumbing and is
  restricted to debug builds.
- **P2 direct gateway** — send packets through the same userspace stack to a
  local SOCKS5 frontend that speaks SOCKS5 inside a TLS 1.3 session to a
  remote gateway phone, which applies the destination policy and exits to the
  Internet. **One gateway hop, no relay.**

The mesh itself (relay node, node discovery, authenticated multi-hop transport,
dynamic routing) is **not implemented**. The long-term product is A (client,
offline) → B (relay) → C (gateway, online) → Internet → WhatsApp; today only
A → C (a single gateway hop) and single-device lab experiments exist.

Constraints inherited from `GOAL_PROMPT.md`:

- Android only, `arm64-v8a` physical devices are the primary target (the
  current evidence is x86_64 emulator only).
- No root, no central transport server.
- WhatsApp is never modified, automated or reverse-engineered.
- All other applications keep their normal connectivity.
- Fail-closed: no silent leak when there is no route.
- No custom cryptography.

---

## 2. Implemented component map

```text
┌────────────────────────────────────────────────────────────────────┐
│ app (com.openmesh.app)                                             │
│  MainActivity / Compose UI                                         │
│   ├─ reads VpnStateStore.status                                    │
│   ├─ VpnControl.start/stop ──► VpnPreferences + service intent     │
│   └─ shows Link / Route / Internet separately                      │
└─────────────────────────────┬──────────────────────────────────────┘
                              │ startForegroundService
┌─────────────────────────────▼──────────────────────────────────────┐
│ vpn:android  OpenMeshVpnService : VpnService, SocketProtector       │
│  ├─ VpnService.prepare + Builder                                   │
│  │    session "OpenMesh VPN", MTU 1500                             │
│  │    IPv4 10.111.0.2/32, IPv6 fd00:111::2/128                     │
│  │    routes 0.0.0.0/0 and ::/0                                    │
│  │    DNS 1.1.1.1, 9.9.9.9                                         │
│  │    addAllowedApplication(targetPackage)                         │
│  ├─ P0: CaptureOnlyEngine  ── reads and drops TUN packets          │
│  ├─ P1: LocalSocksFrontend ── loopback SOCKS5 + direct connector   │
│  ├─ P2: RemoteSocksFrontend ─ TLS 1.3 to a remote gateway          │
│  │         ▲                                                       │
│  │         │ SOCKS5 over loopback                                  │
│  │    Tun2SocksEngine (hev-socks5-tunnel 2.20.0, JNI)              │
│  └─ publishes VpnStatus; 1 s stats; onRevoke; clean stop           │
└───────────────┬───────────────────────────┬────────────────────────┘
                │                           │
┌───────────────▼──────────┐   ┌────────────▼───────────────────────┐
│ vpn:tun2socks            │   │ gateway                            │
│  Tun2SocksConfig (YAML)  │   │  Socks5Server (CONNECT, DNS-only   │
│  Tun2SocksEngine (JNI)   │   │   UDP ASSOCIATE)                   │
│  pinned AAR, 4 ABIs      │   │  DestinationPolicy                 │
└──────────────────────────┘   │  DirectSocketConnector             │
                               │  UdpDnsRelay, GatewayMetrics/Audit │
│  GatewayServer (TLS 1.3 listener)  │
                               └────────────┬───────────────────────┘
                                            │ direct sockets
                                            ▼
                                         Internet

transport      TLS 1.3 contexts, node identity (EC P-256 PKCS#12),
               SHA-256 certificate pinning

core:model      pure Kotlin models, VpnStatus, typed errors
core:protocol   OMSH v1 MeshFrame + FrameCodec (not yet on any transport)
```

### 2.1 Module responsibilities

| Module | Type | Responsibility | Depends on |
|---|---|---|---|
| `app` | Android app | Compose UI, VPN permission launcher, target installation check, service control, gateway service (foreground) | `core:model`, `vpn:android`, `gateway`, `transport` |
| `testclient` | Android app (debug lab) | Generates DNS/HTTP/HTTPS traffic and a negative policy check | Android framework only |
| `core:model` | JVM library | `MeshNode`, capabilities, route/gateway models, `VpnStatus`, typed `OpenMeshException` | — |
| `core:protocol` | JVM library | `MeshFrame`, `FrameType`, strict bounded `FrameCodec` | — |
| `core:routing` | JVM library | Reserved for P6 | — |
| `gateway` | JVM library | Restricted SOCKS5 server, destination policy, direct connector, DNS-only UDP relay, metrics/audit, TLS gateway listener, pinned client connector | `core:model`, `core:protocol`, `transport`, coroutines |
| `vpn:tun2socks` | Android library | YAML config, JNI lifecycle around the pinned hev AAR, stats/log access | AAR |
| `vpn:android` | Android library | `VpnService`, TUN builder, capture engine, lab and remote SOCKS frontends, remote gateway probe, state store/control | `core:model`, `gateway`, `vpn:tun2socks` |
| `transport` | JVM library | TLS 1.3 client/server contexts, node identity (self-signed EC P-256 in PKCS#12), SHA-256 pinning X509 trust manager | `core:model`, Bouncy Castle |
| `relay` | JVM library (placeholder) | Reserved for opaque forwarding (P3) | — |
| `discovery:ble` | Android library (placeholder) | Reserved for BLE discovery (P4) | — |

The Android dependency direction is one-way: `app → vpn:android → gateway /
vpn:tun2socks → core`. `core` has no Android dependency and is testable on the
JVM.

---

## 3. VPN service lifecycle

`OpenMeshVpnService` is a foreground service with
`android:foregroundServiceType="specialUse"` and the
`android.permission.BIND_VPN_SERVICE` permission. Only the system can bind it.

### 3.1 Start

1. The UI calls `VpnService.prepare()`. If consent is missing, the system
   activity is launched.
2. `VpnControl.start()` persists `VpnStartOptions` (target package, lab flag,
   lab allowlist, gateway host/port/fingerprint/token) before starting the
   service. The service reloads these options so a system-initiated restart
   cannot silently change the target or the gateway it trusts.
3. `onStartCommand` calls `startForeground()` immediately, then enters
   `startTunnel`.
4. `startTunnel` resets status counters, checks the target package, calls
   `VpnService.prepare()` again, and builds the TUN.
5. In lab mode the loopback SOCKS server is started **before** the TUN is
   established, so tun2socks has a destination as soon as it starts.
6. The TUN is established with default routes and the per-app allowlist.
7. The selected engine starts.
8. State becomes `TUNNEL_READY` (lab) or `CAPTURING` (P0), and the notification
   text is updated.

If any step throws, the already-created resources are closed in reverse order
and the service enters `ERROR`, with the message stored in `lastError` and shown
by the UI.

### 3.2 Stop and failure

`stopTunnel()` is idempotent and synchronized. It:

1. marks the service inactive;
2. cancels the stats job;
3. stops the capture engine or native engine;
4. closes the loopback SOCKS server (which closes its clients);
5. closes the TUN descriptor;
6. resets counters and sets `Link = DOWN`, `Route = NONE`,
   `Internet = UNKNOWN`;
7. removes the foreground notification if it was started.

`onRevoke()` is implemented: the tunnel is closed, the phase becomes `REVOKED`,
and the service stops. `onDestroy()` performs the same cleanup defensively.

### 3.3 Target allowlist

Only `addAllowedApplication(targetPackage)` is used. OpenMesh itself is **not**
in the allowlist, so its own sockets stay outside the tunnel; this is the
primary loop-prevention mechanism. `VpnService.protect()` is also applied to
outbound sockets and the SOCKS listener as defence in depth. There is no
`addDisallowedApplication`, so all other apps use the underlying network
normally.

Debug builds substitute `com.openmesh.testclient`; release builds target
`com.whatsapp`. See ADR 0003.

### 3.4 TUN parameters

| Parameter | Value | Rationale |
|---|---|---|
| Session | `OpenMesh VPN` | Visible to the user in Android settings |
| MTU | 1500 | Matches the lab engine config |
| IPv4 address | `10.111.0.2/32` | A single tunnel address; no LAN prefix |
| IPv6 address | `fd00:111::2/128` | Unique-local address, not a global claim |
| IPv4 route | `0.0.0.0/0` | Default route inside the VPN for the allowlisted app |
| IPv6 route | `::/0` | Same, for IPv6; captured rather than silently leaked |
| DNS | `1.1.1.1`, `9.9.9.9` | Sent into the tunnel, not resolved on the underlying network |
| Allowed app | one package | Per-app VPN |
| Configure intent | app launcher | The notification opens OpenMesh |

The TUN configuration itself is fail-closed: if the engine is absent, the
packets are read from the TUN and discarded instead of being reinjected.

---

## 4. P0 capture-only data path

`CaptureOnlyEngine` exists to satisfy P0 and to be the safe release default:

- It sets the TUN descriptor non-blocking where possible and uses `Os.poll()`
  with a 500 ms timeout, so shutdown does not depend on a wake-up.
- It reads up to 64 KiB per iteration, counts packets and bytes, and **does not
  write anywhere**.
- Counters are published at most every 500 ms; the UI shows TX > 0 and RX = 0.
- `onStopped(reason)` reports unexpected termination, which the service turns
  into a visible `ERROR` state.

This is intentionally not a "connected" mode. The UI says
`Capturing · fail-closed` and explains that packets are dropped.

---

## 5. P1 lab direct SOCKS data path

This path exists only in debug builds and only when the operator enables
**Lab direct SOCKS (P1)**.

```text
allowlisted app
   │  IP packets
   ▼
TUN (10.111.0.2/32, fd00:111::2/128, default routes, DNS 1.1.1.1/9.9.9.9)
   │  file descriptor owned by VpnService
   ▼
hev-socks5-tunnel 2.20.0 (native, JNI TProxyStartService)
   │  SOCKS5 clients to 127.0.0.1:<ephemeral>
   ▼
LocalSocksFrontend — Socks5Server
   │  RFC 1929 user/password, random per session, max 128 connections
   │  handshake 15 s, idle 300 s, connect 10 s
   ▼
destination policy on every resolved address
   │  blocks private, loopback, link-local, CGNAT, multicast, reserved
   │  one debug-only exception: 10.0.2.2:8080
   ▼
DirectSocketConnector → TCP socket
   │  protected from the VPN where the platform provides an overload
   ▼
destination (host test server or public Internet)

DNS: UDP/53 datagrams from the tunnel reach the same SOCKS server via UDP
ASSOCIATE, but UdpDnsRelay forwards **only port 53** to 1.1.1.1/9.9.9.9.
Everything else is dropped and counted.
```

Properties that are deliberately enforced:

- The SOCKS listener binds `127.0.0.1:0` (ephemeral port), never `0.0.0.0`.
- Credentials are fresh per session and never persisted or logged.
- The only private destination exception is `10.0.2.2:8080`, and it is supplied
  by the debug app, not by the gateway module.
- `DestinationPolicy.approve()` validates the resolved address list and the
  connector dials only an approved address; this prevents DNS rebinding between
  validation and connection.
- Generic UDP is not advertised. Non-DNS UDP is dropped.
- The UI labels this path as a plumbing test, not a gateway and not an Internet
  verification; route and Internet stay `NONE`/`UNKNOWN`.

What this path does **not** prove:

- that WhatsApp works;
- that the gateway is remote;
- that traffic survives a relay;
- that IPv6 egress works end to end (IPv6 is captured and tunnelled, but only
  IPv4 destinations were exercised);
- that the design is safe to expose on a LAN. It must not be exposed.

---

## 6. P2 direct TLS gateway data path

This path exists in every build variant. In the app it is selected by
**Direct gateway (P2)**; the gateway itself is a foreground service on the other
phone.

```text
client phone A                                      gateway phone C
┌────────────────────────────────┐   TCP + TLS 1.3   ┌──────────────────────────────┐
│ allowlisted app                │   pin + token     │ GatewayService (foreground)  │
│   │ IP packets                 │                   │  GatewayServer               │
│   ▼                            │                   │   TlsContexts.server()       │
│ TUN (10.111.0.2/32,            │ ─────────────────► │   TLS 1.3, EC P-256 identity │
│      fd00:111::2/128)          │                   │  per-connection TLS socket   │
│   │                            │                   │   connection initializer:    │
│   ▼                            │                   │    Socks5Server over TLS     │
│ hev-socks5-tunnel 2.20.0       │                   │     RFC 1929 token check     │
│   │ SOCKS5 to 127.0.0.1        │                   │   DestinationPolicy          │
│   ▼                            │                   │   DirectSocketConnector      │
│ RemoteSocksFrontend            │                   └───────────────┬──────────────┘
│   SocksFrontend + DNS resolver │                                   │
│   RemoteGatewayConnector       │◄──────────────────────────────────┘
│     pinned TLS client socket   │     one connection per destination │
│     Socks5Client over TLS      │                                   ▼
└────────────────────────────────┘                                Internet
```

Flow of a single TCP connection:

1. tun2socks resolves the hostname through the frontend's DNS resolver, which
   sends the query as **DNS-over-TCP** inside the authenticated gateway session
   to `1.1.1.1:53`/`9.9.9.9:53`. No DNS leaves the client on the local network.
2. tun2socks opens a SOCKS5 connection to the loopback frontend.
3. `RemoteGatewayConnector` opens (or reuses for the same destination) a TCP
   connection, performs the TLS 1.3 handshake with the pinned fingerprint, and
   runs `Socks5Client` inside that TLS socket: RFC 1929 username/password
   authentication, then `CONNECT host:port`.
4. The gateway decodes the same SOCKS5 bytes from the TLS socket, applies the
   destination policy, and opens a direct socket to the destination.
5. Bytes are piped both ways until either side closes. Counters and the session
   gauges on both phones move.

Verification and honesty:

- `RemoteGatewayProbe` performs a real probe (TLS + token + `CONNECT
  example.com:443`, then a nested TLS 1.3 handshake and HTTP HEAD expecting
  2xx/3xx) before `route = SELECTED` is published, and again for
  `internet = VERIFIED`. It runs every 30 s while the tunnel is up.
- On any failure (unreachable, refused, pin mismatch, token rejected, probe
  failed) the frontend reports no route: the TUN stays up, captured traffic is
  dropped by tun2socks because no connector is available, and the UI shows
  `Route = NONE`, `Internet = FAILED` with the reason.
- There is no multiplexing: one TCP+TLS session per destination. This is a
  deliberate simplicity/correctness trade-off, documented in ADR 0006.

What this path does **not** prove:

- that WhatsApp works (the debug build captures `com.openmesh.testclient`);
- that a relay B exists — P2 is a single hop A → C;
- that the gateway is safe to expose to a hostile network (no mutual TLS, no
  quotas, no revocation, no fuzzing);
- physical `arm64-v8a` behaviour, or a LAN run without `adb forward`;
- IPv6 egress (IPv6 DNS answers are returned, but only IPv4 destinations were
  exercised).

---

## 7. State model and UI honesty

`VpnStatus` keeps independent fields:

- `phase`: `DISCONNECTED`, `WAITING_PERMISSION`, `CONNECTING`, `CAPTURING`,
  `TUNNEL_READY`, `NO_ROUTE`, `ERROR`, `REVOKED`;
- `link`: `UP` / `DOWN` — the TUN exists;
- `route`: `SELECTED` / `NONE` — a gateway route was selected by a router;
- `internet`: `VERIFIED` / `FAILED` / `UNKNOWN` — an end-to-end check.

The UI renders the three rows independently and includes a footer stating that
a link or a running engine does not prove Internet connectivity.

In the P2 path these states are earned, not configured: `route = SELECTED` is
published only after a real TLS + token + `CONNECT` probe to the gateway, and
`internet = VERIFIED` only after a nested TLS 1.3 handshake and an HTTP
2xx/3xx response through that gateway. When the probe fails, the status returns
to `NO_ROUTE` with the failure reason, even though the TUN and the engine keep
running.

Counters are session-scoped. `stopTunnel()` explicitly zeroes them so a stopped
UI cannot look active.

The status store is process-wide and in-memory. It is not persisted, because a
stale "connected" badge after process death would be a lie.

---

## 8. Threading and concurrency

| Component | Threading |
|---|---|
| `VpnService` callbacks | Android main thread |
| `startTunnel` / `stopTunnel` | synchronized on a private lock; resource teardown is idempotent |
| TUN capture (P0) | dedicated daemon thread, `Os.poll` + non-blocking reads |
| hev engine | native worker thread(s); JNI calls are synchronized in `Tun2SocksEngine` |
| Stats monitor | `CoroutineScope(SupervisorJob + Dispatchers.IO)`, 1 s interval, cancelled on stop |
| SOCKS server | daemon accept thread + cached worker pool; semaphore-limited active clients |
| SOCKS pipes | two worker threads per connection, `CountDownLatch` for completion; sockets closed on stop |
| DNS UDP relay | dedicated receive thread + fixed 4-worker pool, 16-permit semaphore |
| P2 frontend (`RemoteSocksFrontend`) | cached IO scope; one connector per destination, cleaned up when the last destination closes |
| P2 probe | `CoroutineScope(Dispatchers.IO)`, every 30 s, cancelled on stop |
| Gateway listener | daemon accept thread + cached worker pool (same pool shape as the SOCKS server) |
| UI | Compose collects `StateFlow` with lifecycle awareness |

Known limits: `Socks5Server.close()` calls `shutdownNow()`, so in-flight pipe
threads are interrupted; this is acceptable for a lab frontend but a production
gateway should drain gracefully with a bounded deadline.

---

## 9. Planned P3+ architecture (not implemented)

The following is a design direction. None of it is wired into the app, and
`relay`, `core:routing` and `discovery:ble` contain no production code.

### 9.1 What remains of the original P2 design

P2 shipped a narrower version than originally designed. Implemented: TCP, TLS
1.3, a pinned self-signed gateway certificate, a per-gateway token and a direct
A → C path. Still missing from the original design:

```text
Client A                                            Gateway C
┌──────────────┐   TCP + TLS 1.3 (mutual auth)   ┌──────────────────┐
│ TUN/tun2socks│ ──────────────────────────────► │ Mesh listener    │
│ SOCKS5 client│   inner stream: destination    │  ↓ authenticated │
│              │   + payload (opaque to relays)  │ Socks5Server     │
└──────────────┘                                 │  ↓ policy        │
                                                 │ outbound sockets │
                                                 └──────────────────┘
```

- Mutual TLS: today the client verifies the gateway (pinned fingerprint) and the
  gateway authenticates the client with a bearer token. Client certificates, or
  a Keystore-backed key the gateway can verify, are not implemented.
- An inner end-to-end channel (OMSH frames inside the TLS session) so a relay
  sees only ciphertext and frame metadata. Today SOCKS5 is carried directly
  inside TLS, which is only safe for a single hop with no relay.
- Connection multiplexing: one TCP+TLS session per destination is currently the
  behaviour; a pooled transport with stream IDs would reduce handshake cost.

### 9.2 Relay B

- Accepts an authenticated outer connection from A and opens/uses one toward C.
- Forwards OMSH frames by `streamId` without parsing application payloads.
- Enforces per-stream credit, buffer caps, idle timeouts and hop limits.
- Cannot resolve or connect destinations; it has no gateway role.

### 9.3 Why TCP+TLS first

TCP+TLS 1.3 is available on every Android version in scope, is easy to audit,
and lets the team focus on authentication and stream semantics. QUIC can replace
the transport later behind the same `MeshTransport` interface; the VPN and
SOCKS5 layers must not depend on the transport choice.

### 9.4 Routing and discovery

`core:routing` is reserved for a small neighbour/route table with authenticated
announcements, expiry, loop prevention and gateway selection. BLE discovery and
Wi-Fi Direct are later phases (P4/P5). No routing protocol is implemented.

---

## 10. Failure behaviour summary

| Failure | Behaviour |
|---|---|
| Target package missing | Service refuses to start; UI shows the package name |
| VPN consent missing | Phase `WAITING_PERMISSION`; no TUN |
| Another VPN active / `establish()` null | Typed error; no leak path |
| tun2socks native library missing (unsupported ABI) | Typed error, TUN closed, UI `ERROR` |
| tun2socks stops unexpectedly | Stats monitor detects it, stops tunnel, UI `ERROR` |
| Local SOCKS server cannot bind | Typed error; no TUN established |
| Destination is private / blocked | SOCKS5 failure, counted as denied, no socket opened |
| DNS upstream unavailable | Query dropped after 5 s per upstream; no local fallback |
| VPN revoked by the system | `REVOKED`, tunnel closed |
| User taps stop | `DISCONNECTED`, counters reset, notification removed |
| No gateway configured (P0 / release default) | Capture-only drain/drop; no silent leak |
| Gateway unreachable (P2) | Probe fails; `NO_ROUTE` with `gateway unreachable or untrusted`; captured traffic is dropped by tun2socks with no connector |
| Gateway certificate does not match the pin (P2) | TLS handshake aborted; `NO_ROUTE`; no SOCKS request is sent |
| Gateway token rejected (P2) | `SOCKS5 authentication failed`; gateway `denied` counter increases; `NO_ROUTE` |
| Gateway probe (probe destination) fails | `Internet=FAILED` while `Route` stays `SELECTED` |

---

## 11. Known architectural gaps

1. No relay, no multi-hop transport, no OMSH frames on the wire: P2 is a single
   A → C hop.
2. No mutual TLS and no Keystore-backed client key: the gateway authenticates
   the client with a bearer token only.
3. No route selection among multiple gateways, so `RouteState.SELECTED` is
   "the configured gateway answered", not "the best of several".
4. No persistence, quotas, per-node limits or revocation UI.
5. The lab SOCKS server is loopback-only and debug-only; it is not a production
   gateway listener.
5. `Socks5Server` implements DNS-only UDP ASSOCIATE. `GOAL_PROMPT.md` suggested
   deferring UDP ASSOCIATE; this narrow exception is required for DNS and is
   documented in ADR 0004.
6. No physical-device evidence. `arm64-v8a` support is provided by the pinned
   AAR but has not been executed on hardware.
7. No instrumentation tests yet. The current evidence is unit tests plus
   emulator UI/logcat/server logs.

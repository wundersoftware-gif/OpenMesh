# OpenMesh Security and Privacy

OpenMesh is designed to carry **third-party traffic**. A bug is not only a
crash: it can leak a user's WhatsApp metadata, turn a phone into an open proxy,
or expose a home network. This document states the threat model, the controls
that actually exist, and the controls that are still missing.

> **Current scope:** P0, P1 and P2 (direct gateway over TLS 1.3, single hop).
> There is no relay, no mesh discovery, no mutual TLS and no Keystore-backed
> identity yet. Do not deploy this build as a privacy or censorship
> circumvention tool.

---

## 1. Assets

| Asset | Why it matters |
|---|---|
| WhatsApp traffic and metadata | Reveals contacts, timing, IP addresses and message sizes |
| Destination hostnames/IPs | Sensitive even without payload |
| Device identity and keys | Impersonation, unauthorized gateway use |
| Gateway owner's Internet access | Abuse, cost, legal exposure, LAN exposure |
| Client's local network | A gateway must not become a bridge into private LANs |
| Availability/battery/data | A malicious peer could drain a phone |
| Logs and diagnostics | Must not become a secondary source of personal data |

## 2. Trust boundaries

```text
[ allowlisted app ]        untrusted application content
        │ TUN
[ OpenMesh VPN service ]   trusted local component (this code)
        │ loopback SOCKS (P1 lab) / TLS 1.3 to one gateway (P2)
[ gateway phone ]          authenticated (pinned certificate + token),
        │ sockets           policy-checked; NOT trusted with LAN access
[ Internet destination ]   untrusted
```

- The **client device** is trusted to enforce its own VPN and policy.
- A **relay** is untrusted for confidentiality: it must only see ciphertext and
  routing metadata. A relay is trusted only to forward or drop, never to read.
- A **gateway** is trusted to open the requested destinations after policy
  checks, but it is not trusted with more metadata than necessary. The gateway
  owner is a separate administrative domain from the client.
- **Nearby does not mean trusted.** Physical proximity grants no authority; all
  nodes must authenticate cryptographically.

## 3. Adversaries considered

1. A malicious or compromised relay that wants to read, modify, replay or
   profile traffic.
2. A malicious gateway that wants to redirect traffic, scan the client's
   private network, or log destinations.
3. A malicious client that wants to abuse a gateway as an open proxy, scan
   private ranges, or exhaust resources.
4. A passive network observer on the physical link.
5. An active network attacker (MITM, injected frames, downgrade).
6. A malicious app on the same device trying to bypass the VPN or read logs.
7. A thief or forensic examiner with access to a powered-off/online device.
8. The user making a mistake (leaving the gateway enabled, installing an
   unverified build).

Out of scope for the MVP: a compromised Android OS/kernel, a physically
compromised device with unlocked bootloader, global traffic correlation by an
ISP-level adversary, and legal coercion of the gateway owner.

---

## 4. Threat and mitigation matrix

Status values: **Implemented**, **Partial**, **Planned**, **Not implemented**.

| # | Threat | Mitigation | Status |
|---|---|---|---|
| T1 | WhatsApp traffic escapes outside the tunnel when no gateway exists | Capture-only TUN drains and drops; default routes; no direct fallback | Implemented |
| T2 | Other apps are captured by accident | `addAllowedApplication` with exactly one package; Chrome verified outside | Implemented (substitute app) |
| T3 | DNS leaks to the local resolver | DNS servers are inside the TUN. P1: DNS-only UDP relay to fixed public resolvers. P2: queries are sent as DNS-over-TCP **inside the gateway TLS session** to `1.1.1.1`/`9.9.9.9`; no DNS leaves the client on the local network | Implemented (lab) |
| T4 | IPv6 leaks around an IPv4-only tunnel | TUN has an IPv6 address and `::/0` route; IPv6 is captured and tunnelled, not bypassed | Implemented (capture); end-to-end IPv6 not verified |
| T5 | Generic UDP/QUIC is silently sent direct | Only port 53 datagrams are accepted; all other UDP is dropped and counted | Implemented |
| T6 | Open SOCKS proxy on the LAN/Internet | P1 lab listener binds `127.0.0.1:0` with per-session random credentials. P2 gateway binds `0.0.0.0:8443` and requires TLS 1.3 with a pinned certificate plus a per-gateway token before any SOCKS byte is read | Partial — the gateway listener is public by design and untested against a hostile network |
| T7 | Unauthorized SOCKS client | Per-session random RFC 1929 credentials (P1 lab); per-gateway token inside TLS 1.3 (P2); a wrong token is rejected before any CONNECT is served | Implemented (lab) |
| T8 | Gateway used to reach private/loopback/LAN services | `DestinationPolicy` blocks private, loopback, link-local, CGNAT, multicast, reserved; exact lab exception only | Implemented |
| T9 | DNS rebinding between check and connect | Policy validates the resolved list; connector dials only an approved address | Implemented |
| T10 | Resource exhaustion by many connections | Semaphores (64 server / 128 lab), handshake/idle/connect timeouts, 4 KiB DNS cap, 64 KiB frame cap | Implemented (basic) |
| T11 | Relays read stream content | No relay exists (P2 is a single hop). Planned inner end-to-end OMSH channel before any relay ships | Planned (P3) |
| T12 | Gateway impersonation | TLS 1.3 with the SHA-256 fingerprint of the gateway's self-signed EC P-256 certificate pinned on the client; a mismatch aborts the handshake. No mutual direction yet: the client is authenticated with a bearer token | Implemented (client side, single hop); mutual TLS Planned |
| T13 | Replay of old authenticated sessions | TLS 1.3 fresh handshakes per connection; the gateway token is a bearer secret, not a signed challenge, so a stolen token is valid until regenerated | Partial |
| T14 | MITM or downgrade on the mesh link | TLS 1.3 only, no plaintext fallback, certificate pinning instead of hostname verification | Implemented (single hop) |
| T15 | Malicious client abuses gateway quota/battery | Planned per-node quotas, bandwidth limits, consent screen and revocation | Not implemented |
| T16 | Gateway owner cannot tell who is using their link | Gateway shows aggregate counters, not per-client identity; a per-token identity table is planned | Partial |
| T17 | Logs capture message content or contacts | No payload/URL/DNS question logging; audit interface is metadata-only; current logs are the system logcat and counters | Partial |
| T18 | Stale "connected" state after process death | Status is in-memory; stop resets counters and states; no persisted badge | Implemented |
| T19 | Debug lab features ship in release | The lab frontend is created only when `labDirectSocks` is true, which only the debug UI sets; the exported debug control receiver lives only in the debug source set; the `10.0.2.2:8080` allowlist is injected by the debug app only | Implemented (needs release review) |
| T20 | VPN loops through OpenMesh's own sockets | OpenMesh is not in the allowlist; `protect()` used as defence in depth; the P2 gateway sockets are created by the gateway process, outside the client tunnel | Implemented |
| T21 | Silent failure leaves the user thinking traffic works | Link/Route/Internet are separate; P2 publishes `Route=SELECTED`/`Internet=VERIFIED` only after a real probe and falls back to `NO_ROUTE`/`FAILED` on error | Implemented |
| T22 | Malformed frames cause memory exhaustion or crashes | Strict bounded codec: 36-byte header, 64 KiB cap, unknown version/type/flags rejected, truncation errors | Implemented (codec); transport not wired |
| T23 | Device theft exposes keys | The gateway private key is a PKCS#12 file in app-private storage and the token is SharedPreferences; neither is Keystore-backed | Partial (P2 uses non-Keystore storage) |
| T24 | Malicious app reads OpenMesh logs | Logs are app-private; no external storage; notification has no sensitive content; the gateway logs its fingerprint, not client identities | Implemented |
| T25 | Gateway dials the client's LAN | `DestinationPolicy` blocks private, loopback, link-local, CGNAT, multicast and reserved ranges on every resolved address, on the gateway as well as on the lab frontend | Implemented |
| T26 | Wrong gateway pinned by mistake (mis-pairing) | The fingerprint is 64 hex characters entered out of band and compared exactly; a wrong pin produces a visible `NO_ROUTE` state, never a silent fallback to a direct socket | Implemented (single hop) |

---

## 5. Controls that exist today, in detail

### 5.1 Per-application capture

`VpnService.Builder.addAllowedApplication(target)` is the only capture filter.
No `addDisallowedApplication` is used, so every other app keeps normal
connectivity. In P1 evidence, Chrome loaded `example.org` while the VPN was
active; see `docs/evidence/p1-chrome-outside-vpn.png`.

The debug target substitution (`com.openmesh.testclient`) is shown in the UI.
The release target is `com.whatsapp`, but no release run has touched WhatsApp.

### 5.2 Fail-closed behaviour

- TUN routes are default routes (`0.0.0.0/0`, `::/0`); the allowlisted app has
  no path around the tunnel.
- Capture-only mode reads and discards. It cannot fall back to a direct socket.
- Lab mode either starts its loopback frontend before establishing the TUN or
  fails the whole start; there is no "TUN up but engine missing" state.
- If tun2socks stops, the monitor fails the tunnel rather than leaving a
  half-working interface.
- If the destination policy denies, no socket is opened and the SOCKS request
  fails.

Evidence: `docs/evidence/p0-failclosed-2026-11-17.txt` shows DNS failure, TCP
timeouts from source `10.111.0.2`, RX = 0 and no host-server requests.

### 5.3 Loopback SOCKS server (lab only)

- Binds `127.0.0.1` on an ephemeral port.
- Requires RFC 1929 username/password generated with `SecureRandom` for each
  session; credentials are never persisted or logged.
- Rejects no-auth when credentials are configured.
- Rejects `BIND` and unknown commands.
- Supports `UDP ASSOCIATE` **only** to forward DNS (port 53) to fixed upstreams.
- Limits: 128 concurrent clients in the lab, 15 s handshake, 300 s idle,
  10 s connect, 32 KiB copy buffers.
- Closed on VPN stop; all client sockets are closed.

### 5.4 Destination policy

`DestinationPolicy` rejects:

- `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10` (CGNAT), `127.0.0.0/8`,
  `169.254.0.0/16`, `172.16.0.0/12`, `192.168.0.0/16`, `224.0.0.0/4` and above;
- `::`, `::1`, `fc00::/7`, `fe80::/10`, `ff00::/8`, and IPv4-mapped private
  addresses;
- any host where **any** resolved address is blocked (mixed answers are
  rejected);
- empty resolutions, invalid ports.

The lab exception is an exact `host:port` match, set by the debug app to
`10.0.2.2:8080`. A second live listener on `10.0.2.2:8081` was denied,
demonstrating exact matching rather than "private IPs are allowed in debug".

### 5.5 DNS handling

- Android sends the allowlisted app's DNS to `1.1.1.1`/`9.9.9.9` inside the TUN.
- tun2socks forwards UDP to the loopback SOCKS server.
- The DNS relay accepts only port 53, only from loopback, only non-fragmented
  datagrams with a valid header, and only up to 4096 bytes.
- Upstream queries go to the configured public resolvers; no local resolver is
  used as a fallback.
- DNS question names are not logged. Responses are not inspected or cached.

Residual risk: the chosen upstream resolver sees the client's queries. In P1
DNS is not encrypted end-to-end. In P2 the query travels as DNS-over-TCP inside
the gateway's TLS 1.3 session, so the local network and the gateway's ISP see
only the resolver, not the client.

### 5.6 Logging and data minimisation

- The UI shows counters, phases, target package and errors.
- `GatewayAudit` is metadata-only (`sessionId`, host, port, byte counts). It is
  currently a no-op in the lab frontend.
- There is no analytics SDK, no crash reporter, no cloud endpoint, no account,
  no advertising ID, no contact access and no storage permission.
- `GatewayMetrics` keeps aggregate counters, not destination history.
- Debug logs (`tun2socks.log`) belong to the native engine and are app-private;
  they are deleted at the start of each session. They can contain connection
  metadata, so they must not be shared without review.

### 5.7 No custom cryptography

OpenMesh contains no cipher, hash, key exchange or random-number construction of
its own. It uses the platform TLS 1.3 implementation for every byte that leaves
a device in P2, `SecureRandom` for SOCKS credentials, and Bouncy Castle **only**
to generate the self-signed gateway certificate (a standard X.509/EC P-256
builder, not a custom construction). No protocol, MAC, KDF or RNG of OpenMesh's
own design exists anywhere in the codebase.

### 5.8 P2 gateway controls

What the client enforces before any packet is proxied:

- The gateway certificate must be the exact self-signed EC P-256 certificate
  whose SHA-256 fingerprint the operator pinned out of band. Hostname
  verification is intentionally disabled because the certificate is not issued
  by a public CA; the pin **is** the authentication. `TlsContexts` installs
  `PinnedFingerprintTrustManager`, which compares the SHA-256 of the presented
  leaf certificate and aborts otherwise.
- The TLS version is pinned to `TLSv1.3`; there is no plaintext or downgraded
  mode.
- The per-gateway token is sent as an RFC 1929 username/password pair **inside**
  the TLS session, after the handshake. A wrong token fails at the SOCKS layer
  and no `CONNECT` is served.
- One TLS session per destination. Sessions are cached per destination while
  the tunnel is up and closed on stop.
- DNS is sent as DNS-over-TCP to fixed public resolvers inside an authenticated
  session; no UDP DNS leaves the client.

What the gateway enforces:

- The listener binds `0.0.0.0:8443` (foreground service, visible notification,
  stop action). It does not start automatically at boot.
- The destination policy runs on every resolved address on the gateway side, so
  a client cannot use the gateway to reach the gateway's own LAN.
- Connection limits (64 concurrent), handshake (15 s), idle (300 s) and connect
  (10 s) timeouts are inherited from `Socks5Server`.
- Counters (`accepted`, `denied`, bytes) are visible to the gateway owner.

What is **not** enforced yet (see §6): no mutual TLS, no per-client identity or
revocation, no quotas, no token storage in the Android Keystore, no fuzzing of
the TLS/SOCKS surface, and no rate limiting per token.

Evidence: `docs/evidence/p2-direct-gateway-2026-11-17.txt` — positive run,
gateway-down fail-closed run, wrong-token run and wrong-pin run.

---

## 6. Missing controls (must be built before P3/P4 and any public release)

1. **Mutual TLS.** The gateway verifies the client only with a bearer token.
   Client certificates, or a Keystore-backed client key the gateway can verify,
   do not exist.
2. **Token and key storage.** The gateway token is SharedPreferences and the
   gateway private key is a PKCS#12 file, both app-private but not
   Keystore-backed, not hardware-protected and not rotatable without manual
   re-pairing.
3. **End-to-end inner channel.** Without it, a relay could read destinations
   and payloads. This is mandatory before any multi-hop claim.
4. **Gateway consent and revocation.** No UI to opt in per client, no client
   list, no per-client revoke, no quotas.
5. **Quotas and rate limits.** Current limits are per-process, not per-node;
   there is no data cap, bandwidth shaping or battery policy.
6. **Replay protection at the application layer.** TLS 1.3 protects each
   connection, but the bearer token is replayable until regenerated; a signed
   challenge or short-lived ticket is needed.
7. **Abuse detection.** No reputation, no anomaly detection, no alerting.
8. **Physical hardening.** No attestation, no tamper evidence, no locked-screen
   key policy.
9. **IPv6 end-to-end verification.** IPv6 is captured but untested beyond the
   TUN; only IPv4 destinations were exercised.
10. **Gateway listener hardening.** The TLS/SOCKS surface has not been fuzzed
    or exposed to a hostile network; it needs fuzzing, connection-drain limits
    and a formal review before that changes.

---

## 7. Residual risks in the current build

| Risk | Severity | Why it is acceptable for P2 today | Required before release |
|---|---|---|---|
| WhatsApp itself never tested | High | Explicitly out of scope until hardware is available; no claim made | P7 on physical devices |
| Debug target is not WhatsApp | Medium | Lab-only substitution; visible in UI; release targets WhatsApp | Physical P0/P1/P2 with WhatsApp |
| P2 is a single hop | High | No relay exists, so no relay can read anything; the UI says "direct gateway", hops=1 | P3 with an encrypted inner channel |
| Bearer token is replayable | High | Token is entered out of band, lives in app-private storage, and is regenerable in the UI | mTLS or signed challenges |
| Gateway key and token not Keystore-backed | Medium | App-private storage only; device theft while the gateway runs is the exposure window | Keystore migration + screen lock policy |
| Gateway listener is public (`0.0.0.0:8443`) | High | TLS 1.3 + pin + token + destination policy; visible foreground service with a stop action; untested against a hostile network | Fuzzing, quotas, revocation, review |
| DNS upstream sees query names | Medium | No local leak; names not logged; DNS travels inside TLS on the P2 link | DoH/DoT upstream or destination resolver |
| Emulator-only evidence | High | x86_64 only; no arm64 hardware run | Physical arm64 matrix |
| No fuzzing of TLS/SOCKS/frames | Medium | Lab scope; unit and socket-level tests cover the happy path and the auth/pin failures | Fuzz + review before exposure |
| Native library supply chain | Medium | AAR pinned by SHA-256, MIT, source commit recorded | Reproducible rebuild / SBOM |

---

## 8. Lab-specific warnings

- The only private destination allowed is `10.0.2.2:8080`, the Android emulator
  host loopback alias. It must never appear in a release build.
- The P1 debug SOCKS server is bound to `127.0.0.1`; do not change it to
  `0.0.0.0` to "test from another device".
- The P2 evidence used an `adb forward` on one host with two emulators. On a
  real LAN the gateway is reachable by anything on that network, so run the
  gateway only on a network you trust and stop it when you are finished.
- Do not use the lab path on a phone with real accounts. It is a plumbing test,
  not a privacy tool.
- The host test server is an unauthenticated development server. Bind it to
  localhost if other machines share the network, or stop it when finished.
- The debug build exports `DebugControlReceiver` so adb can start and stop the
  services. It exists only in the debug source set; never add it to a release.

---

## 9. Gateway deployment checklist

Status before a gateway is exposed to other users:

- [x] TLS 1.3 listener with a pinned self-signed certificate (client side) and
      a per-gateway token.
- [x] Destination policy on every resolved address, IPv4 and IPv6 ranges.
- [x] DNS with no name logging and no local fallback (P2 uses DNS-over-TCP
      inside TLS).
- [x] Kill switch semantics: no route means captured traffic is dropped.
- [ ] TLS 1.3 **mutual** authentication with per-node identity and revocation.
- [ ] End-to-end inner channel; relay cannot parse `OPEN_STREAM` or data.
- [ ] Explicit owner consent, visible connected peers and one-tap revoke.
- [ ] Per-node connection, bandwidth, data and battery limits.
- [ ] Fuzzed TLS/SOCKS parsers; bounded memory under attack.
- [ ] Audit log review: metadata only, retention limit, user-visible.
- [ ] Kill switch verified on physical devices, including reboot/revoke.
- [ ] Threat model reviewed independently; no custom crypto.
- [ ] Legal/terms review for sharing Internet access.

---

## 10. Reporting a vulnerability

Do not open a public issue with exploit details. Contact the maintainer
privately, include the affected build hash, reproduction steps and impact. Until
a security contact is published, treat this repository as a research prototype.

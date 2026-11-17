# ADR 0006 — P2 as a direct TLS 1.3 gateway, not a mesh relay

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** OpenMesh implementation team

## Context

`GOAL_PROMPT.md` phases the project: P0 capture, P1 TUN + tun2socks + SOCKS5,
P2 an authenticated remote gateway, P3 an opaque relay, P4/P5 discovery and
transports, P6 routing, P7 real WhatsApp over A → B → C. The phase gate says
BLE, Wi-Fi Direct and multi-hop must not start before the earlier phases are
validated.

The original P2 design (documented in `docs/ARCHITECTURE.md` before this ADR)
described a mesh listener with mutual TLS, an inner end-to-end OMSH channel and
relay-visible ciphertext only. That is the correct **P3** shape: it only makes
sense once a relay exists to be defended against.

The team needed something that works now, with real evidence, to prove the
client data path can reach the Internet through another phone.

## Decision

Ship P2 as a **single-hop direct gateway**:

```text
A (client) ── TCP + TLS 1.3 (pinned certificate + token) ── C (gateway) ── Internet
```

- The gateway is a foreground service (`GatewayService`) running
  `GatewayServer`: a TLS 1.3 listener on `0.0.0.0:8443` that accepts one
  connection per destination and runs the existing restricted `Socks5Server`
  on the decrypted stream.
- Gateway identity: a self-signed EC P-256 certificate generated with Bouncy
  Castle and persisted as PKCS#12 (`openmesh-gateway-identity.p12`) so the
  fingerprint survives restarts.
- Client authentication of the gateway: SHA-256 **certificate pinning**
  (`PinnedFingerprintTrustManager`), configured out of band. Hostname
  verification is disabled because the certificate is self-signed; the pin is
  the check.
- Gateway authentication of the client: an RFC 1929 username/password token
  sent **inside** TLS. One token per gateway, generated on the gateway,
  displayed in its UI, entered on the client, stored in app-private
  preferences.
- Wire format on the link: SOCKS5 (CONNECT only) inside TLS, one session per
  destination, no multiplexing. No OMSH frames — see `docs/PROTOCOL.md` §11.
- DNS: DNS-over-TCP to `1.1.1.1:53`/`9.9.9.9:53` through the gateway session;
  no UDP DNS leaves the client.
- Honesty: `RemoteGatewayProbe` performs a real probe (TLS + token + `CONNECT
  example.com:443`, nested TLS 1.3, HTTP HEAD 2xx/3xx) before
  `Route=SELECTED`/`Internet=VERIFIED` are published, every 30 s.
- Fail-closed: unreachable, refused, wrong pin or wrong token produce
  `NO_ROUTE` and the captured traffic has no connector, so it is dropped.
- Scope control: no relay, no BLE, no Wi-Fi Direct, no routing table. P3+ stays
  behind the phase gate.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Implement the full mesh P2 now (mutual TLS + OMSH inner channel + relay-ready framing) | Much more code before any evidence exists; the relay-specific protections have nothing to protect against yet; higher risk of a long stretch with nothing runnable |
| Use plain SOCKS5 over TCP without TLS | Any device on the path could read destinations and payloads, and the gateway would be an open proxy on the LAN |
| Use a public CA certificate for the gateway | Requires a domain name and public DNS for a phone that may be NATed; pinning a self-signed fingerprint is simpler and works on a LAN |
| Tunnel the traffic through an existing VPN app or `tun2socks`' upstream TLS | No authenticated gateway identity, no per-gateway token, no destination policy |
| Mutual TLS with Keystore-backed client certificates now | Correct long term, but it needs a certificate lifecycle (issue, rotate, revoke) that does not exist; deferred with the token as the interim control (SECURITY.md §6) |
| Multiplex all destinations over one TLS session | Correct for efficiency later; adds stream-ID state and head-of-line blocking risks before the simple path is proven |

## Consequences

- P2 gives real, reproducible evidence: A → C → Internet over authenticated
  TLS 1.3 in an emulator pair, with fail-closed behaviour proven in three
  separate fault injections.
- The gateway is exposed on `0.0.0.0:8443` by design. It is protected by TLS +
  pin + token + destination policy, but it has **not** been fuzzed or exposed to
  a hostile network. This is a documented residual risk, not a claim of safety.
- The bearer token is replayable until regenerated; mutual TLS and Keystore
  storage are explicit prerequisites for P3 (SECURITY.md §6).
- Because SOCKS5 is carried directly inside TLS, the path is only safe with no
  relay in the middle. P3 must wrap the stream in OMSH frames with an
  end-to-end inner channel before any relay ships.
- No OMSH code runs on the wire yet; `core:protocol` remains a tested codec and
  a contract.
- Physical phones, LAN runs and WhatsApp remain unvalidated; the evidence is
  two x86_64 emulators on one host with an `adb forward`.

## Verification

- `GatewayTlsTest` (5 tests): pinned TLS + token + CONNECT; wrong fingerprint
  rejected; wrong token rejected; private destination denied; DNS-over-TCP.
- `TlsLoopbackTest` (5 tests): TLS 1.3 handshake over loopback, pinning
  behaviour, identity generation.
- Emulator evidence, `docs/evidence/p2-direct-gateway-2026-11-17.txt`:
  positive run (DNS 1146 ms, HTTP 200/206 B, HTTPS 200/577 B with
  `TLS_AES_128_GCM_SHA256`, decoy denied), gateway-down fail-closed, wrong
  token, wrong fingerprint, identity persistence across a gateway restart.

## References

- `GOAL_PROMPT.md` §phases
- `gateway/src/main/kotlin/com/openmesh/gateway/GatewayServer.kt`
- `gateway/src/main/kotlin/com/openmesh/gateway/client/RemoteGatewayConnector.kt`
- `transport/src/main/kotlin/com/openmesh/transport/` (`TlsContexts`,
  `TlsIdentity`, `PinnedFingerprintTrustManager`, `Fingerprint`)
- `docs/PROTOCOL.md` §11
- `docs/SECURITY.md` §5.8, §6
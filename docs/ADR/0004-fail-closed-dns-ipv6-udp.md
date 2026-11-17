# ADR 0004 — Fail-closed capture, IPv6 and DNS-only UDP

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** OpenMesh implementation team

## Context

The MVP may support only TCP and DNS at first, but it must not pretend that
generic UDP/QUIC or IPv6 work. A half-configured dual-stack tunnel that silently
leaks IPv6, or a UDP path that bypasses the gateway, would violate the core
security requirement.

`GOAL_PROMPT.md` §10 requires:

- DNS resolved through the gateway, not the local connection;
- unsupported UDP explicitly blocked or rejected;
- IPv6 tested, or explicitly blocked if not implemented correctly;
- no dual-stack configuration that appears to work while leaking.

## Decision

### Fail-closed by default

- Release builds and debug builds without lab mode run **capture-only**: the TUN
  is read and every packet is dropped. There is no fallback to the underlying
  network.
- The TUN has default IPv4 and IPv6 routes, so the allowlisted app has no
  alternate path around it.
- If the selected engine cannot start, the service tears down the TUN and
  reports an error. It never leaves a TUN without an engine.

### IPv6

- The TUN is configured with `fd00:111::2/128` and `::/0`.
- IPv6 is therefore captured and passed to tun2socks, not leaked around an
  IPv4-only tunnel.
- The lab destination policy checks IPv6 addresses, including IPv4-mapped
  forms.
- End-to-end IPv6 egress has **not** been verified. Documentation must say so.

### UDP

- Generic UDP is **not supported**.
- tun2socks may forward UDP to the local SOCKS5 server, but the server only
  supports `UDP ASSOCIATE` for DNS:
  - datagrams must come from loopback;
  - the destination port must be 53;
  - fragmentation is rejected;
  - payload must be 12..4096 bytes;
  - queries are forwarded only to the configured upstreams
    (`1.1.1.1:53`, `9.9.9.9:53`).
- Every other UDP datagram is dropped and counted. There is no direct UDP
  fallback.
- QUIC and other UDP-based WhatsApp features are therefore expected to fail or
  fall back; this must be measured in P7, not assumed.

### DNS

- Android is told to use `1.1.1.1` and `9.9.9.9` inside the TUN.
- Queries travel through tun2socks and the loopback SOCKS server to the DNS
  relay.
- DNS question names are not logged.
- No local resolver is used as a fallback.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Enable IPv6 only if it works, otherwise leave it unconfigured | Unconfigured IPv6 can still be used by apps or leak; explicit capture is safer |
| Drop IPv6 entirely at the TUN | An app could still attempt IPv6 over the underlying network if no route is captured; default route + drop is clearer |
| Implement generic UDP now | Requires an authenticated UDP tunnel, NAT state, QUIC considerations and abuse controls; out of scope for P0/P1 |
| Use the local resolver for DNS | Violates the no-leak requirement |
| Forward DNS to the SOCKS request's destination | Would allow arbitrary UDP-like abuse and bypass the fixed resolver policy; current relay intentionally ignores the requested address and only accepts port 53 |
| Pretend UDP works and let QUIC fail silently | Dishonest and unobservable; explicit drop + counters is verifiable |

## Consequences

- Some WhatsApp features may not work in the MVP (likely calls, status media or
  QUIC paths). This is documented as unknown until P7.
- DNS is only as private as the chosen upstream resolver in P1. The name is not
  logged locally, but the resolver sees it.
- The DNS-only `UDP ASSOCIATE` is a narrow deviation from the initial suggestion
  to defer UDP ASSOCIATE entirely. It is required to resolve names through the
  tunnel and is explicitly limited to DNS. It must not be widened without a new
  ADR.
- IPv6 is captured but not proven end to end; the release remains fail-closed
  because everything is dropped without a gateway.

## Verification

- P0: DNS failed, TCP to live listeners timed out from `10.111.0.2`, RX stayed
  0, and no server received a request.
- P1: DNS succeeded through the tunnel; HTTP/HTTPS succeeded through the local
  SOCKS path.
- Code tests: `DestinationPolicyTest` covers private IPv4/IPv6, IPv4-mapped
  addresses and mixed answers.
- `UdpDnsRelay` enforces port 53, loopback source, fragmentation rejection and
  payload bounds by construction; no dedicated test yet (listed as a gap).

## References

- `GOAL_PROMPT.md` §8, §9, §10
- `vpn/android/src/main/kotlin/com/openmesh/vpn/android/OpenMeshVpnService.kt`
- `gateway/src/main/kotlin/com/openmesh/gateway/socks5/UdpDnsRelay.kt`
- `docs/TEST_PLAN.md` §5

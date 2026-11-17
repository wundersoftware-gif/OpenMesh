# ADR 0002 — tun2socks engine: hev-socks5-tunnel 2.20.0

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** OpenMesh implementation team

## Context

P1 requires a userspace TCP/IP stack that consumes the Android TUN descriptor
and turns captured TCP/UDP into SOCKS5 streams, without root and without kernel
NAT. `GOAL_PROMPT.md` asks to compare a Rust/smoltcp implementation, sing-box,
and other maintained options, and to record license, ABI, JNI integration,
TCP/UDP and IPv4/IPv6 support in an ADR.

The engine must:

- accept the TUN file descriptor from `VpnService`;
- speak SOCKS5 to a local frontend;
- support Android ABIs, especially `arm64-v8a`;
- be small enough to audit and wrap with a narrow JNI interface;
- have a permissive license compatible with distribution;
- be maintained and version-pinned.

## Decision

Use **hev-socks5-tunnel 2.20.0** (MIT), pinned as:

- File: `vpn/tun2socks/libs/hev-socks5-tunnel-2.20.0.aar`
- SHA-256: `AE8A2E96B80B4ECCCCD037805A4BB4A278090C89DBCAE8BC6FCB7634E9A255B9`
- Upstream source commit recorded at selection time:
  `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`
- JNI class: `hev.htproxy.TProxyService`
- Methods used: `TProxyStartService(configPath, tunFd)`,
  `TProxyStopService()`, `TProxyIsRunning()`, `TProxyGetStats()`
- ABIs present in the AAR: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`

The wrapper is `vpn:tun2socks` (`Tun2SocksEngine`, `Tun2SocksConfig`). The native
engine owns the TUN descriptor while running; the service keeps the
`ParcelFileDescriptor` open until the engine stops. The engine is configured via
a generated YAML file:

```yaml
tunnel:
  mtu: 1500
  ipv4: 10.111.0.2
  ipv6: 'fd00:111::2'
socks5:
  port: <ephemeral>
  address: 127.0.0.1
  udp: 'udp'
  username: '<random>'
  password: '<random>'
misc:
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  log-file: '<app files>/tun2socks.log'
  log-level: info
  limit-nofile: 4096
```

`TProxyGetStats()` returns four values in this order:
`[txPackets, txBytes, rxPackets, rxBytes]`. The UI maps them accordingly.

## Alternatives considered

| Alternative | Assessment |
|---|---|
| Rust + smoltcp custom engine | Maximum control, but a large implementation and validation project (TCP state machine, buffers, IPv6, UDP). Not justified for P1; would delay evidence. |
| sing-box | A full proxy platform with a much larger surface, configuration model and licensing considerations. More than a tun2socks component. |
| badvpn-tun2socks | Older C codebase; maintenance and Android/JNI packaging need separate verification. |
| Kernel NAT / `iptables` | Requires root; explicitly out of scope. |
| Android `VpnService` without tun2socks | Cannot terminate arbitrary TCP from a TUN without a userspace stack. |

## Consequences

- The project does not build native code, so no NDK toolchain is required to
  build the APK. This improves reproducibility on Windows.
- The AAR is a binary dependency. It is pinned by SHA-256, its license is
  included, and the source commit is recorded, but the repository does not
  rebuild it. A future SBOM/reproducible-build task should verify or rebuild it.
- If an unsupported ABI is used, `TProxyStartService` throws
  `UnsatisfiedLinkError`; the wrapper converts it to a typed start failure and
  the service fails closed.
- The engine's log may contain connection metadata. It is app-private, reset per
  session, and must be reviewed before being shared.
- Generic UDP is forwarded by the engine to SOCKS. The local server only accepts
  DNS, so non-DNS UDP is dropped (ADR 0004).

## Evidence

- P1 run: engine reported `tun2socks`, counters moved, DNS/HTTP/HTTPS succeeded.
- `Tun2SocksConfigTest`: 5 tests.
- AAR hash verified on 2026-11-17.
- License: `third_party/hev-socks5-tunnel/LICENSE` (MIT, Copyright (c) 2022 hev).

## References

- `GOAL_PROMPT.md` §3 (tun2socks), §6, §10
- `vpn/tun2socks/src/main/kotlin/com/openmesh/vpn/tun2socks/`
- `docs/TEST_PLAN.md` §4

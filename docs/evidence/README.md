# Evidence index

These files were captured on **2026-11-17** using the debug build and the
bundled lab traffic client. P0/P1 used the `openmesh_test` emulator; P2 used
**two** emulators (`emulator-5554` gateway, `emulator-5556` client) with an
`adb forward` standing in for a LAN. All of it is x86_64, Android 16 / API 36,
substitute-app evidence — **not** WhatsApp evidence and **not** physical-device
evidence.

| File | What it shows |
|---|---|
| `p2-direct-gateway-2026-11-17.txt` | P2 transcript: positive run (DNS-over-TCP, HTTP 200, HTTPS 200 nested TLS 1.3, decoy denied), gateway-down fail-closed, wrong token, wrong pin, identity persistence, and what it does not prove |
| `p2-app-gateway-running.png` | OpenMesh UI on the gateway phone: gateway RUNNING on 8443, pinned fingerprint and token |
| `p1-testclient-2026-11-17.txt` | P1 run: DNS, HTTP 200 at 10.0.2.2:8080, HTTPS 200 with TLS 1.3, live decoy at 10.0.2.2:8081 denied, counters |
| `p1-testclient-run.png` | Test client UI after the P1 run |
| `p1-app-counters.png` | OpenMesh UI showing Tunnel ready, Link UP, Route NONE, Internet UNKNOWN, counters |
| `p1-chrome-outside-vpn.png` | Chrome loading example.org while the VPN was active (outside the allowlist) |
| `p0-failclosed-2026-11-17.txt` | P0 fail-closed run: DNS failure, TCP timeouts from 10.111.0.2, no host requests, RX = 0 |
| `p0-testclient-failclosed.png` | Test client UI after the P0 run |
| `p0-app-failclosed.png` | OpenMesh UI showing Capturing · fail-closed, TX > 0, RX 0 |
| `host-test-server-8080.log` | Host server log: P1 request at 13:05:11Z, nothing during P0 |
| `host-test-server-8081.log` | Decoy listener log: no requests, proving the policy denial |

The screenshots are raw `adb exec-out screencap -p` PNGs. The P2 transcript
quotes verbatim logcat lines; whitespace was wrapped for readability only.

To reproduce, see `README.md` §6 (P1 in 6.4, P2 in 6.5, P0 in 6.6) and
`docs/TEST_PLAN.md` §9. Exact pixel coordinates are not part of the procedure.

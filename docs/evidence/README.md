# Evidence index

The files below were captured with the debug build and the bundled lab traffic
client. The P0/P1 files and the first P2 transcript are from **2026-11-17** on
the `openmesh_test` emulator; the P2 emulator-to-emulator run used two
emulators (`emulator-5554` gateway, `emulator-5556` client) with an `adb
forward` standing in for a LAN. The **2026-11-18** files add a physical Redmi
13C (HyperOS 2.0, Android 15, `arm64-v8a`), used as client and as gateway with
an emulator at the other end.

None of this is WhatsApp evidence, and no run used two physical phones.

| File | What it shows |
|---|---|
| `p2-physical-hardware-2026-11-18.txt` | P2 on a physical Redmi 13C: phone as client (HTTP 200, HTTPS 200 TLS 1.3, decoy denied, fail-closed with the phone's Wi-Fi up) and phone as gateway over real Wi-Fi (DNS, HTTPS 200, counters, identity persistence) |
| `p2-emulator-client-via-redmi.png` | Emulator client UI: Link UP, Route SELECTED, Internet VERIFIED while connected to the physical Redmi gateway at 192.168.1.58:8443 |
| `p2-direct-gateway-2026-11-17.txt` | P2 emulator transcript: positive run (DNS-over-TCP, HTTP 200, HTTPS 200 nested TLS 1.3, decoy denied), gateway-down fail-closed, wrong token, wrong pin, identity persistence, packet-level DNS no-leak proof, and what it does not prove |
| `p2-app-gateway-running.png` | OpenMesh UI on the emulator gateway: gateway RUNNING on 8443, pinned fingerprint and token |
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

To reproduce, see `README.md` §6 (P1 in 6.4, P2 in 6.5 with the physical variant
in 6.5.1, P0 in 6.6) and `docs/TEST_PLAN.md` §9. Exact pixel coordinates are not
part of the procedure.

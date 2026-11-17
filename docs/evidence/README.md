# Evidence index

These files were captured on **2026-11-17** from the `openmesh_test` emulator
(x86_64, Android 16 / API 36) using the debug build and the bundled lab traffic
client. They are not WhatsApp evidence and not physical-device evidence.

| File | What it shows |
|---|---|
| `p1-testclient-2026-11-17.txt` | P1 run: DNS, HTTP 200 at 10.0.2.2:8080, HTTPS 200 with TLS 1.3, live decoy at 10.0.2.2:8081 denied, counters |
| `p1-testclient-run.png` | Test client UI after the P1 run |
| `p1-app-counters.png` | OpenMesh UI showing Tunnel ready, Link UP, Route NONE, Internet UNKNOWN, counters |
| `p1-chrome-outside-vpn.png` | Chrome loading example.org while the VPN was active (outside the allowlist) |
| `p0-failclosed-2026-11-17.txt` | P0 fail-closed run: DNS failure, TCP timeouts from 10.111.0.2, no host requests, RX = 0 |
| `p0-testclient-failclosed.png` | Test client UI after the P0 run |
| `p0-app-failclosed.png` | OpenMesh UI showing Capturing · fail-closed, TX > 0, RX 0 |
| `host-test-server-8080.log` | Host server log: P1 request at 13:05:11Z, nothing during P0 |
| `host-test-server-8081.log` | Decoy listener log: no requests, proving the policy denial |

The screenshots are raw `adb exec-out screencap -p` PNGs. The transcripts are
verbatim UI text collected with `uiautomator dump`; whitespace was wrapped for
readability only.

To reproduce, see `README.md` §6 and `docs/TEST_PLAN.md` §9. Exact pixel
coordinates are not part of the procedure.

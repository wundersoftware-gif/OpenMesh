# OpenMesh Test Plan and Results

This document separates what was actually executed from what is planned. A test
is only "passed" when the command, environment and observed output are
reproducible.

---

## 1. Test environment

| Item | Value |
|---|---|
| Host | Windows 11, PowerShell |
| JDK | 21.0.7 LTS (`C:\Program Files\Java\jdk-21`) |
| Gradle | 9.5.1 (wrapper) |
| AGP | 9.0.1 |
| Kotlin | 2.3.20 |
| Android SDK | 36, build-tools 36.0.0 |
| Emulated runtime | Android emulators `openmesh_test` (P0/P1, P2 gateway) and `vp_test` (P2 client) |
| Emulated ABI | x86_64 |
| Emulated Android | 16 / API 36 |
| Emulated fingerprint | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` |
| Emulated display | 1080 × 2400 |
| Physical runtime (2026-11-18) | Redmi 13C `23108RN04Y`, serial `VWNNCQEIMJ997T9L`, HyperOS `OS2.0` (MIUI `V816`) |
| Physical ABI | **`arm64-v8a`**, `armeabi-v7a`, `armeabi` |
| Physical Android | **15 / API 35**, 720 × 1600, 320 dpi |
| Physical network | Wi-Fi `WUNDER`, `192.168.1.58/24` (PC Ethernet `192.168.1.17/24`) |
| Debug app | `com.openmesh.app.debug` version `0.2.0-p2-debug` |
| Lab client | `com.openmesh.testclient` version `0.2.0-p2` |
| WhatsApp-targeting debug app (2026-11-18) | `com.openmesh.app.debug` version `0.2.0-p2-debug-whatsapp`, built with `-PopenmeshTarget=com.whatsapp`, SHA-256 `09209178FC46692BBA6CEEB140ACFC55FCF5FDFB3B08A3996AE86345DF873322` |
| Host server | `node tools/test-server.mjs` on `0.0.0.0:8080` |
| Decoy server | same script on `0.0.0.0:8081` |
| P2 emulator link | `adb -s emulator-5554 forward tcp:8443 tcp:8443`; the client dials `10.0.2.2:8443` |
| P2 physical links | phone as client: `adb reverse tcp:8443 tcp:8443`; phone as gateway: emulator dials `192.168.1.58:8443` over real Wi-Fi, no forwarding |
| VPN consent | pre-granted with `appops set ... ACTIVATE_VPN allow` for unattended runs |

On 2026-11-18 the Redmi 13C had WhatsApp installed **and registered**, and the
WhatsApp-targeting debug build was run there (§4.8). That run proves capture and
connectivity, not message delivery. Every other result is substitute-app
evidence. Physical runs still use **one real phone at a time** with an emulator
at the other end.

Artifacts (the ones attached to the `v0.2.0-p2` release):

- Debug APK SHA-256: `97be392b90eb8c8a6855a2a13daf5fc4a393f5108b74b92a19e552df9b28984e`
- Lab client APK SHA-256: `00e95eb35734c52e1b70d8a2b1ac2071a4dadd0da3e7435121f0f6e23b3605e9`
- tun2socks AAR SHA-256: `AE8A2E96B80B4ECCCCD037805A4BB4A278090C89DBCAE8BC6FCB7634E9A255B9`

Artifact hashes change whenever the sources change; re-hash before comparing a
new run.

---

## 2. Automated unit and socket tests

Command:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat test
```

Result on 2026-11-18: **BUILD SUCCESSFUL**, 55 tests, 0 failures, 0 errors.

| Suite | Tests | What it proves |
|---|---|---|
| `core.protocol.FrameCodecTest` | 17 | Round-trip, max payload, rejects bad magic/version/type/flags/length/trailing bytes, truncation, invalid node ID, stream reads |
| `gateway.socks5.DestinationPolicyTest` | 8 | Public allowed; RFC1918, loopback, link-local, CGNAT, multicast, ULA, IPv4-mapped private blocked; mixed answers blocked; exact lab allowlist; invalid port/empty resolution |
| `gateway.socks5.Socks5ServerTest` | 5 | Authenticated CONNECT round-trip over real sockets; no-auth mode; wrong password denied; private destination denied; BIND rejected with `0x07` |
| `vpn.tun2socks.Tun2SocksConfigTest` | 5 | YAML content, quoted credentials, path escaping, invalid port/MTU, half credentials |
| `transport.TlsLoopbackTest` | 5 | TLS 1.3 client/server handshake over real sockets, pinned-fingerprint trust behaviour, identity generation and reload |
| `gateway.GatewayTlsTest` | 5 | Real TLS gateway + pinned client over loopback: successful CONNECT with the token, wrong fingerprint rejected, wrong token rejected, private destination denied, DNS-over-TCP |
| `gateway.DnsQueryFilterTest` | 10 | AAAA answered with an empty NOERROR response, transaction ID/opcode/RD preserved, A/HTTPS/non-IN/multi-question/already-a-response/malformed forwarded unchanged, no upstream connection opened for AAAA |

The SOCKS and TLS tests use real sockets and real handshakes; they are not
mocks. The lab allowlist is used only to reach `127.0.0.1:<echo port>` or the
emulator host alias.

Build checks:

```powershell
.\gradlew.bat :app:assembleDebug :testclient:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Both succeeded. The release artifact is `app-release-unsigned.apk`; it has not
been installed, signed or run. It targets `com.whatsapp`, is **not** attached to
any release, and must not be treated as validated.

---

## 3. Functional test matrix

Status: **PASS**, **PARTIAL**, **NOT RUN**, or **BLOCKED**.

| ID | Test | Method | Result |
|---|---|---|---|
| VPN-001 | VPN permission request | App flow + `VpnService.prepare`; consent pre-granted with appops for automation | **PARTIAL** — success path implemented; the system consent dialog and denial path were not exercised |
| VPN-002 | TUN creation | `adb shell ip addr show tun0` while active | **PASS** — `10.111.0.2/32` and `fd00:111::2/128` |
| VPN-003 | Per-app allowlist | Only `com.openmesh.testclient` configured; debug target substitution | **PASS for substitute**; WhatsApp release target **NOT RUN** |
| VPN-004 | Chrome outside VPN | Chrome loaded `https://example.org/` while VPN active | **PASS** |
| VPN-005 | No tunnel loop | TUN → tun2socks → loopback SOCKS; OpenMesh not allowlisted; lab traffic completed | **PASS (lab)** — no loop observed |
| VPN-006 | Fail-closed with no route | Capture-only mode, live servers on 8080/8081 | **PASS** — DNS failed, TCP timed out from `10.111.0.2`, no server request, RX = 0 |
| VPN-007 | Stop and reset | UI Stop VPN | **PASS** — `tun0` gone, phase `Disconnected`, counters 0, notification removed |
| VPN-008 | Revoke handling | `onRevoke` implemented | **NOT RUN** — not triggered on the emulator |
| GW-001 | SOCKS5 CONNECT | Unit socket test + P1 HTTP/HTTPS + P2 HTTPS | **PASS** |
| GW-002 | DNS through the tunnel | Test client resolves `example.com`; P1 uses DNS-only UDP relay; P2 uses DNS-over-TCP through the gateway | **PASS (local lab)**, **PASS (P2 emulator pair)** and **PASS (P2 physical phone in each role)** |
| GW-003 | Private destination rejected | Unit tests + live decoy on `10.0.2.2:8081` | **PASS** — denied in ~575 ms while the port was listening, on both P1 and P2 |
| GW-004 | Unknown client rejected | Wrong RFC 1929 password (P1) and wrong gateway token (P2) | **PASS** — auth failure, denied counter, `NO_ROUTE` |
| GW-005 | BIND rejected | Raw SOCKS request | **PASS** — reply `0x07` |
| GW-006 | Connection limits/timeouts | Code inspection + unit coverage of config | **PARTIAL** — not load-tested |
| MESH-001 | Client to remote gateway | TLS 1.3 with pinned fingerprint and token: two emulators; then a physical Redmi 13C as client and as gateway (one at a time) | **PASS (A → C only)** — not a mesh, no relay, not two physical phones |
| MESH-002 | Client → relay → gateway | Requires P3 | **NOT IMPLEMENTED** |
| MESH-003 | Relay disconnect invalidates route | Requires P3/P6 | **NOT IMPLEMENTED** |
| MESH-004 | Gateway disconnect → no egress | Gateway stopped while the P2 VPN is up, in both topologies | **PASS (emulator and physical)** — `NO_ROUTE`, `Internet=FAILED`, all fetches fail; on the physical client the phone kept a working Wi-Fi uplink and still got no direct egress; no DNS packet observed outside the tunnel |
| SEC-001 | Manipulated frame rejected | 17 codec tests | **PASS (codec)**; no OMSH frame on any wire yet |
| SEC-002 | Replay rejected | Wrong token and wrong pin on a live TLS gateway | **PASS (token/pin rejection)**; the bearer token itself remains replayable until regenerated |
| SEC-003 | Wrong gateway rejected | Client pinned to a different fingerprint | **PASS (emulator)** — `gateway certificate fingerprint mismatch` |
| SEC-004 | Gateway cannot reach client LAN | Destination policy on the gateway, live private decoy on `10.0.2.2:8081` | **PASS (emulator)** — connection reset, `denied` counter |
| WA-001 | Send WhatsApp text | Requires physical devices + accounts | **BLOCKED** |
| WA-002 | Receive WhatsApp text | Requires physical devices + accounts | **BLOCKED** |
| WA-003 | Send image | Requires physical devices + accounts | **BLOCKED** |
| WA-004 | Notification with screen off | Requires physical devices + accounts | **BLOCKED** |

---

## 4. P1 evidence (2026-11-17, ~13:05 UTC)

Procedure:

1. Install debug app + lab client.
2. Start host servers on 8080 and 8081.
3. Enable **Lab direct SOCKS (P1)** and start the VPN.
4. Launch the test client with `autoStart=true`.
5. Read the test client, OpenMesh UI and both server logs.

Observed:

```text
TUN: 10.111.0.2/32, fd00:111::2/128, default routes, DNS 1.1.1.1/9.9.9.9
OpenMesh: Tunnel ready, Link UP, Route NONE, Internet UNKNOWN
Engine: tun2socks; TX 6.0 KB, RX 20.3 KB; 67 out / 48 in

DNS example.com -> 172.66.147.243, 104.20.23.154,
                   2606:4700:10::6814:179a, 2606:4700:10::ac42:93f3 (1494 ms)
HTTP http://10.0.2.2:8080/health -> 200, 206 bytes, 744 ms
HTTPS https://example.com/ -> 200, 577 bytes, TLS_AES_128_GCM_SHA256, 1094 ms
HTTP http://10.0.2.2:8081/health -> denied after 96 ms (live listener)
Host 8080 log: GET /health -> 200, 206 B, from 127.0.0.1:62058
Host 8081 log: no requests
```

Why this is meaningful:

- The test client is the only captured package; the traffic source for the P0
  timeouts was the TUN address `10.111.0.2`.
- The decoy listener on 8081 was running, so the denial is policy, not a dead
  port.
- Host/server timestamps and markers match the run.

What it does **not** prove:

- WhatsApp works.
- A remote gateway or relay exists.
- Internet verification: `Route=NONE`, `Internet=UNKNOWN`.
- IPv6 egress (IPv4 destinations only).
- Physical arm64 behaviour.

Evidence files:

- `docs/evidence/p1-testclient-2026-11-17.txt`
- `docs/evidence/p1-testclient-run.png`
- `docs/evidence/p1-app-counters.png`
- `docs/evidence/p1-chrome-outside-vpn.png`
- `docs/evidence/host-test-server-8080.log`
- `docs/evidence/host-test-server-8081.log`

### 4.6 P2 direct-gateway evidence (2026-11-17, 17:24–17:33 UTC)

Two emulators: `emulator-5554` (gateway, AVD `openmesh_test`) and
`emulator-5556` (client, AVD `vp_test`), joined by `adb forward tcp:8443
tcp:8443` so the client dials `10.0.2.2:8443`. The gateway was started with
`com.openmesh.debug.START_GATEWAY`, the client with `com.openmesh.debug.
START_VPN --es mode gateway --es host 10.0.2.2 --ei port 8443 --es fingerprint
<64 hex> --es token <gateway token>`.

Observed (final artifacts, hashes in §1):

```text
Gateway:  listening on 0.0.0.0:8443 fingerprint=46676594f8054ae4f0dfc6910fbe74e1904ddf65121419eef8541627fae17ed2
Client:   DNS example.com -> 172.66.147.243, 104.20.23.154, 2606:4700:10::6814:179a, 2606:4700:10::ac42:93f3 (1597 ms)
Client:   HTTP http://10.0.2.2:8080/health -> 200, 206 bytes, 672 ms (through the gateway)
Client:   HTTPS https://example.com/ -> 200, 577 bytes, TLS_AES_128_GCM_SHA256, 807 ms (nested TLS 1.3)
Client:   HTTP http://10.0.2.2:8081/health -> denied after 575 ms (live listener, gateway policy)
Client:   phase=TUNNEL_READY, link=UP, route=SELECTED, internet=VERIFIED,
          gatewayId=10.0.2.2:8443, hops=1, rxBytes=29050, txBytes=11810
          probeDetail=example.com answered HTTP 200 via gateway (TLS 1.3)
Gateway:  accepted=31, denied=3, active=1, bytesToRemote=12556, bytesFromRemote=142613
```

Fault injections, all fail closed:

```text
Gateway stopped:      phase=NO_ROUTE, route=NONE, internet=FAILED,
                      probeDetail=gateway unreachable or untrusted: SSLHandshakeException: connection closed
                      every fetch fails; tcpdump on the client shows DNS queries only on tun0
Wrong token:          probeDetail=... Socks5Exception: SOCKS5 authentication failed
Wrong fingerprint:    probeDetail=... gateway certificate fingerprint mismatch;
                      expected 0000...0001, got 4667...7ed2
```

What this emulator-only run does **not** prove: WhatsApp (never installed),
physical phones, `arm64-v8a`, a LAN run without `adb forward`, IPv6 destinations
end to end, relay/multi-hop/BLE/Wi-Fi Direct, hostile-network exposure of the
gateway, mutual TLS, Keystore storage of the token, quotas or revocation.
Android's device-local DNS cache can answer a query after the tunnel attempts
fail; no packet was observed outside the tunnel. The physical runs in §4.7
close the `arm64-v8a` and real-Wi-Fi gaps.

Full transcript, packet capture and the recorded lab-harness failure:
`docs/evidence/p2-direct-gateway-2026-11-17.txt`.

### 4.7 P2 physical-hardware evidence (2026-11-18)

Device: Redmi 13C, HyperOS 2.0 / Android 15 (API 35), `arm64-v8a`, Wi-Fi
`192.168.1.58/24`. Same APK hashes as the release (no code changed).

**Run A — phone as client, emulator as gateway.** The phone had working Wi-Fi
during the whole run, which makes the fail-closed result meaningful. Transport
to the emulator gateway used `adb reverse`/`adb forward`.

```text
DNS example.com -> ... (303 ms)
HTTP http://10.0.2.2:8080/health -> 200, 206 B, 105 ms
HTTPS https://example.com/ -> 200, 577 B, TLS_AES_128_GCM_SHA256, 553 ms
http://10.0.2.2:8081/health -> denied after 55 ms (live listener)
client: phase=TUNNEL_READY, link=UP, route=SELECTED, internet=VERIFIED,
        gatewayId=127.0.0.1:8443, hops=1
gateway: accepted=9, denied=1, bytesToRemote=4593, bytesFromRemote=29512

gateway stopped:
phase=NO_ROUTE, link=UP, route=NONE, internet=FAILED,
probeDetail=gateway unreachable or untrusted: SSLHandshakeException: connection closed
HTTP FAILED after 34 ms; HTTPS FAILED after 86 ms; no leak despite Wi-Fi up
```

**Run B — phone as gateway, emulator as client over real Wi-Fi.** No
forwarding of any kind; the emulator dialled `192.168.1.58:8443`.

```text
phone gateway: listening on 0.0.0.0:8443
               fingerprint=503f092856b576808efda29b19503e98fa3069707644cae1addcb4753f09ce8e
DNS example.com -> ... (310 ms)
HTTPS https://example.com/ -> 200, 577 B, TLS_AES_128_GCM_SHA256, 1265 ms
http://10.0.2.2:8081/health -> denied after 525 ms
http://10.0.2.2:8080/health -> not applicable in this topology (10.0.2.2 is the
                               emulator host alias, not reachable from the phone)
client: phase=TUNNEL_READY, route=SELECTED, internet=VERIFIED,
        gatewayId=192.168.1.58:8443, hops=1
phone:  accepted=7, denied=4, bytesToRemote=2798, bytesFromRemote=28088

gateway stopped:
phase=NO_ROUTE, route=NONE, internet=FAILED,
ConnectException: failed to connect to /192.168.1.58 (port 8443) ... ECONNREFUSED
all fetches fail
```

Identity persistence: stopping and restarting the phone gateway produced the
same fingerprint (`503f09…ce8e`) and the emulator client recovered to
`VERIFIED` on the next probe. Lab pitfalls observed: `adb reverse` occupies
port 8443 on the phone (`EADDRINUSE` if the gateway starts first), and Xiaomi
blocks `adb shell input` without "USB debugging (Security settings)", so the
phone had to be unlocked by hand for screenshots.

What it does **not** prove: WhatsApp, two physical phones in one run, a client
phone with no Internet at all, a two-phone LAN across networks/NAT, IPv6
end-to-end, mTLS/Keystore/quotas/fuzzing.

Full transcript: `docs/evidence/p2-physical-hardware-2026-11-18.txt`.

---

### 4.8 Real WhatsApp through P2 (2026-11-18)

Build: `-PopenmeshTarget=com.whatsapp` (debug-signed, `0.2.0-p2-debug-whatsapp`,
SHA-256 `09209178FC46692BBA6CEEB140ACFC55FCF5FDFB3B08A3996AE86345DF873322`).
Device: the same Redmi 13C, WhatsApp already installed and registered. Only
`com.whatsapp` is in the allowlist. No message was sent by the test harness.

**Run A — loopback gateway on the phone.** The phone ran both the client VPN and
the gateway (`127.0.0.1:8443`). WhatsApp's own log:

```text
WAJMNSStream/impl/establish (endpoint=g.whatsapp.net:443)
WAJMNSStream/impl/report result=ready, port=443, connectedIPVersion=4, connectMS=4
WAJMNSStream/impl/state-changed (connecting=>connected)
client: TUNNEL_READY, route=SELECTED, internet=VERIFIED
gateway: accepted=9, denied=0, active=3
```

**Run B — emulator gateway over `adb reverse`/`adb forward`.** Separate Android
instance as the gateway (pinned fingerprint `4667…7ed2`):

```text
WAJMNSStream/impl/establish (endpoint=g.whatsapp.net:5222)
WAJMNSStream/impl/report result=ready, port=5222, connectedIPVersion=4, connectMS=4
WAJMNSStream/impl/state-changed (connecting=>connected)    10:39:35.850
WAJMNSStream/impl/report result=failure, error=MNSTCPSocketReceive(): Received EOF
WAJMNSStream/impl/state-changed (connected=>disconnected)  10:40:51.398
gateway: accepted=13, denied=16, bytesToRemote=21811, bytesFromRemote=52585
```

The stream lasted **76 s**, ended with a clean EOF (not a reset; the gateway's
SOCKS idle timeout is 300 s), and no reconnect appeared in the next ~9 minutes
of logcat while the screen was locked. No message was delivered or received, so
this is **connectivity evidence, not messaging evidence**. `denied=16` is not
explained: the gateway installs a no-op audit sink, so denial reasons are not
recorded.

The prerequisite fix was **IPv4-only egress**: before `DnsQueryFilter`, WhatsApp
resolved AAAA, connected over IPv6 into the black-holed TUN and failed with
`Connection reset by peer` / `SecureTCP connection establish timeout`. Now
single-question AAAA queries get an empty NOERROR answer while `::/0` stays
captured.

Full transcript: `docs/evidence/whatsapp-p2-2026-11-18.txt`; gateway-side
capture: `docs/evidence/whatsapp-p2-2026-11-18.pcap`.

What it does **not** prove: message delivery, notifications, media, a reconnect
after EOF, two physical phones, a client without Internet, IPv6 egress, or the
A → B → C path.

---

## 5. P0 fail-closed evidence (2026-11-17, ~13:07–13:08 UTC)

Procedure:

1. Stop the VPN.
2. Disable **Lab direct SOCKS (P1)**.
3. Start **Capture-only VPN (P0)**.
4. Run the same test client with both host servers still listening.

Observed:

```text
OpenMesh: Capturing · fail-closed, Link UP, Route NONE, Internet UNKNOWN
TX 3.5 KB, RX 0 B, 59 packets out, 0 in, engine capture

DNS FAILED after 20101 ms: EAI_NODATA
HTTP 10.0.2.2:8080 FAILED after 15038 ms:
  SocketTimeoutException ... from /10.111.0.2 (port 59812)
HTTPS FAILED after 20049 ms: DNS failure
HTTP 10.0.2.2:8081 FAILED after 12013 ms:
  SocketTimeoutException ... from /10.111.0.2 (port 49964)
Host 8080 log: unchanged since 13:05
Host 8081 log: unchanged since startup
```

Interpretation: captured packets were drained and dropped. There was no
fallback to the underlying network. This is the required fail-closed behaviour
for an unavailable gateway.

Evidence files:

- `docs/evidence/p0-failclosed-2026-11-17.txt`
- `docs/evidence/p0-testclient-failclosed.png`
- `docs/evidence/p0-app-failclosed.png`

---

## 6. Stop/reset and outside-VPN checks

| Check | Observed |
|---|---|
| Stop button removes TUN | `Device "tun0" does not exist` |
| Phase after stop | `Disconnected`, Link `DOWN`, Route `NONE`, Internet `UNKNOWN` |
| Counters after stop | TX 0 B, RX 0 B, packets 0, duration `—`, engine `capture` |
| Notification | Removed with foreground stop |
| Chrome outside VPN | `example.org` loaded; `topResumedActivity=ChromeTabbedActivity`; URL and Example Domain content visible |
| 8081 exact-match | Denied while listener live; 8080 allowed |

---

## 7. Not run / blocked

These are **not** passing tests and must not be reported as such:

| Area | Reason | What is needed |
|---|---|---|
| WhatsApp message delivery | Capture and connection are proven; delivery is not (76 s stream, then EOF with the screen locked) | A message actually sent and received with the screen on, plus notifications/media |
| Two physical phones in one P2 run | Only one physical phone available | Two arm64 phones on the same LAN |
| Physical `arm64-v8a` matrix | One phone covered, each role separately (Redmi 13C, Android 15) | More vendors/Android versions; ideally three phones |
| Client phone with no Internet at all | The physical client kept its Wi-Fi uplink | Local transport (BLE/Wi-Fi Direct) and a genuinely offline client |
| Real 3-device mesh A → B → C | P3–P7 not implemented | Relay, TLS transport with inner channel, routing |
| BLE discovery | P4 not implemented | BLE-capable hardware, permissions, lifecycle tests |
| Wi-Fi Direct | P5 not implemented | Multi-vendor hardware, concurrency tests |
| Replay of a stolen gateway token | Bearer token has no expiry or rotation UI | Mutual TLS or signed short-lived tickets |
| IPv6 end-to-end | IPv4-only egress by design: AAAA answered empty, `::/0` captured | IPv6-capable data path and gateway |
| Long-run battery/data | No hardware | Instrumented soak tests |
| Load/fuzz of the TLS + SOCKS gateway surface | Not yet built | Fuzzing harness, connection-drain limits, review |
| Release APK on device | Unsigned and unvalidated; deliberately not distributed | Signing process, physical WhatsApp test plan |
| VPN consent denial/revoke UI | Automated lab pre-granted consent | Manual device test |

---

## 8. Physical test plan (defined, not executed)

### 8.1 Topology

```text
Phone A (client): no mobile data, no Wi-Fi with Internet
Phone B (relay): connected to Phone C over the chosen local link
Phone C (gateway): has Internet (Wi-Fi or mobile data), explicit consent
```

For P7, A must not have any direct Internet path. If A can still reach the
Internet through its own Wi-Fi, the test is invalid.

### 8.2 Steps

1. Install the same signed build on all three phones.
2. On A, select the debug WhatsApp target (release build once P2 exists) and
   start the VPN.
3. On C, enable gateway mode and accept the consent screen.
4. Pair/authenticate A–B and B–C; verify both links in the UI.
5. Confirm A's `Route=SELECTED` (hop count 2) and `Internet=UNKNOWN` until a
   verification probe succeeds.
6. Send a WhatsApp text from A; confirm delivery on a fourth phone.
7. Repeat with an image.
8. Turn the screen off on A and send another message; confirm the notification.
9. Disconnect B; confirm A reports `NO_ROUTE`/`ERROR` and nothing leaks
   (re-run the fail-closed probe).
10. Disconnect C; confirm the same.
11. Record RTT, throughput, bytes per node, reconnect count, battery and CPU.
12. Repeat on at least two hardware vendors.

### 8.3 Measurements

Record before setting numeric goals: discovery time, connect time, RTT,
throughput, loss, reconnects, CPU, battery, bytes per node, hops. The current
P1 latencies (DNS 1.5 s, HTTP 0.7 s, HTTPS 1.1 s) are single emulator samples,
not targets.

---

## 9. How to reproduce the current evidence

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat test :app:assembleDebug :testclient:assembleDebug

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
& "$sdk\emulator\emulator.exe" -avd openmesh_test -no-window -no-audio -gpu swiftshader_indirect
& "$sdk\emulator\emulator.exe" -avd vp_test -port 5556 -no-window -no-audio -gpu swiftshader_indirect
& $adb wait-for-device
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r testclient\build\outputs\apk\debug\testclient-debug.apk
& $adb shell appops set com.openmesh.app.debug ACTIVATE_VPN allow
& $adb shell pm grant com.openmesh.app.debug android.permission.POST_NOTIFICATIONS

# separate terminals
node tools/test-server.mjs
$env:OPENMESH_TEST_PORT='8081'; node tools/test-server.mjs
```

Then follow README §6: P1 in 6.4, **P2 in 6.5 (two emulators)** with the
physical variant in **6.5.1**, P0 in 6.6, outside-VPN check in 6.7. Exact UI
coordinates are not part of the test: use the buttons by label so the run
survives layout changes.

For the packet-level no-leak check (lab only, requires a `userdebug` AVD):

```powershell
& $adb -s emulator-5556 root
& $adb -s emulator-5556 shell "nohup tcpdump -i any -w /data/local/tmp/leak.pcap 'port 53' >/dev/null 2>&1 &"
# run the lab client, then:
& $adb -s emulator-5556 shell "pkill tcpdump; tcpdump -r /data/local/tmp/leak.pcap -n"
```

Every DNS query must appear on `tun0` and none on `eth0`.

---

## 10. Acceptance assessment

| Phase | Acceptance criterion | Assessment |
|---|---|---|
| P0 | VPN activates and traffic is limited to the target package; no connectivity claim | **Met for the substitute app in the emulator**; WhatsApp/release not verified |
| P1 | Target uses TUN → tun2socks → SOCKS5; other apps stay outside; no loops/leaks | **Met for the substitute app in the emulator**; WhatsApp not verified; direct lab egress, not a gateway |
| P2 | A without direct Internet reaches a test destination through C | **Partially met (A → C)** — authenticated TLS 1.3 gateway with honest Route/Internet and fail-closed behaviour proven emulator-to-emulator and with a physical `arm64-v8a` phone in each role, including a real-Wi-Fi gateway reachable without adb forwarding. Real WhatsApp connects through it over IPv4. Missing: two physical phones in one run, a client with no Internet at all, message delivery, and the relay |
| P3 | Real traffic traverses B and exits C; B cannot read it | **Not met** — not implemented |
| P7 | WhatsApp messages over A → B → C | **Not met** — WhatsApp connects to a single gateway, but delivery is unproven and the mesh does not exist |

The project is therefore at the end of **P2 with a single gateway hop**, not at
the MVP. Real WhatsApp connects but has not been shown to deliver a message, and
no run has used two physical phones. The next phase (P3 relay) stays behind the
phase gate until both are true.

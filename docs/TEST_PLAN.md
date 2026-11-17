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
| Runtime | Android emulator `openmesh_test` |
| ABI | x86_64 |
| Android | 16 / API 36 |
| Fingerprint | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` |
| Display | 1080 × 2400 |
| Debug app | `com.openmesh.app.debug` version `0.1.0-p0-debug` |
| Lab client | `com.openmesh.testclient` version `0.1.0` |
| Host server | `node tools/test-server.mjs` on `0.0.0.0:8080` |
| Decoy server | same script on `0.0.0.0:8081` |
| VPN consent | pre-granted with `appops set ... ACTIVATE_VPN allow` for unattended runs |

No physical Android device was connected. No WhatsApp account or installation
exists on the emulator. Every result below is emulator + substitute-app
evidence, not WhatsApp evidence.

Artifacts:

- Debug APK SHA-256: `E6A357DC9E01DADF711FF016C46AB6B09795174B3F220C45DED66F8ACE9B35BD`
- Lab client APK SHA-256: `DDA2EDD5782ECFD6F4A903000AD854CC23CA93BFCB3ACF0A3F27E67D6DAFE008`
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

Result on 2026-11-17: **BUILD SUCCESSFUL**, 35 tests, 0 failures, 0 errors.

| Suite | Tests | What it proves |
|---|---|---|
| `core.protocol.FrameCodecTest` | 17 | Round-trip, max payload, rejects bad magic/version/type/flags/length/trailing bytes, truncation, invalid node ID, stream reads |
| `gateway.socks5.DestinationPolicyTest` | 8 | Public allowed; RFC1918, loopback, link-local, CGNAT, multicast, ULA, IPv4-mapped private blocked; mixed answers blocked; exact lab allowlist; invalid port/empty resolution |
| `gateway.socks5.Socks5ServerTest` | 5 | Authenticated CONNECT round-trip over real sockets; no-auth mode; wrong password denied; private destination denied; BIND rejected with `0x07` |
| `vpn.tun2socks.Tun2SocksConfigTest` | 5 | YAML content, quoted credentials, path escaping, invalid port/MTU, half credentials |

The SOCKS test uses a real loopback echo server and a real `Socks5Server`; it is
not a mock. The lab allowlist is used only to reach `127.0.0.1:<echo port>`.

Build checks:

```powershell
.\gradlew.bat :app:assembleDebug :testclient:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Both succeeded. The release artifact is `app-release-unsigned.apk` and has not
been installed or run. It is capture-only and targets `com.whatsapp`.

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
| GW-001 | SOCKS5 CONNECT | Unit socket test + P1 HTTP/HTTPS | **PASS** |
| GW-002 | DNS through the tunnel | Test client resolves `example.com`; DNS goes through TUN/SOCKS | **PASS (local lab)**; remote gateway DNS **NOT IMPLEMENTED** |
| GW-003 | Private destination rejected | Unit tests + live decoy on `10.0.2.2:8081` | **PASS** — denied in 96 ms while the port was listening |
| GW-004 | Unknown client rejected | Wrong RFC 1929 password | **PASS** — auth failure, denied counter |
| GW-005 | BIND rejected | Raw SOCKS request | **PASS** — reply `0x07` |
| GW-006 | Connection limits/timeouts | Code inspection + unit coverage of config | **PARTIAL** — not load-tested |
| MESH-001 | Client to remote gateway | Requires P2 | **NOT IMPLEMENTED** |
| MESH-002 | Client → relay → gateway | Requires P3 | **NOT IMPLEMENTED** |
| MESH-003 | Relay disconnect invalidates route | Requires P3/P6 | **NOT IMPLEMENTED** |
| MESH-004 | Gateway disconnect → no egress | Requires P2/P6 | **NOT IMPLEMENTED** |
| SEC-001 | Manipulated frame rejected | 17 codec tests | **PASS (codec)**; no transport yet |
| SEC-002 | Replay rejected | Requires TLS transport | **NOT IMPLEMENTED** |
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
| WhatsApp capture and messaging | No physical phone, no WhatsApp account | 3 physical Android devices, WhatsApp accounts, isolated network |
| Physical arm64-v8a | No device attached | At least one arm64 phone; ideally three |
| Real 3-device mesh A → B → C | P2–P7 not implemented | Gateway, TLS transport, relay, routing |
| BLE discovery | P4 not implemented | BLE-capable hardware, permissions, lifecycle tests |
| Wi-Fi Direct | P5 not implemented | Multi-vendor hardware, concurrency tests |
| Replay and MITM | No mesh transport | P2 threat-model tests |
| IPv6 end-to-end | Only IPv4 destinations exercised | IPv6-capable destination and gateway |
| Long-run battery/data | No hardware | Instrumented soak tests |
| Release APK on device | Release is unsigned and capture-only | Signing config, physical WhatsApp test plan |
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
& $adb wait-for-device
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r testclient\build\outputs\apk\debug\testclient-debug.apk
& $adb shell appops set com.openmesh.app.debug ACTIVATE_VPN allow
& $adb shell pm grant com.openmesh.app.debug android.permission.POST_NOTIFICATIONS

# separate terminals
node tools/test-server.mjs
$env:OPENMESH_TEST_PORT='8081'; node tools/test-server.mjs
```

Then follow sections 6.4–6.6 of the README. Exact UI coordinates are not part
of the test: use the buttons by label so the run survives layout changes.

---

## 10. Acceptance assessment

| Phase | Acceptance criterion | Assessment |
|---|---|---|
| P0 | VPN activates and traffic is limited to the target package; no connectivity claim | **Met for the substitute app in the emulator**; WhatsApp/release not verified |
| P1 | Target uses TUN → tun2socks → SOCKS5; other apps stay outside; no loops/leaks | **Met for the substitute app in the emulator**; WhatsApp not verified; direct lab egress, not a gateway |
| P2 | A without direct Internet reaches a test destination through C | **Not met** — not implemented |
| P3 | Real traffic traverses B and exits C; B cannot read it | **Not met** — not implemented |
| P7 | WhatsApp messages over A → B → C | **Not met** — blocked by hardware and missing mesh |

The project is therefore at the end of P1, not at the MVP.

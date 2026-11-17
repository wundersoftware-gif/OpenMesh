# OpenMesh VPN

OpenMesh is an Android **mesh egress VPN**: the traffic of one app is captured
with the official `VpnService` API and forwarded through other Android devices,
so a phone without a working Internet path can use a nearby phone as its
gateway. No root, no central transport server, no modification of the target
app.

The target application is WhatsApp Messenger (`com.whatsapp`).

> **This repository does not implement the full mesh yet.**
> P0 (per-app capture), the P1 lab data path (TUN → tun2socks → SOCKS5) and
> **P2 (direct gateway over authenticated TLS 1.3, A → C → Internet)** are
> implemented and have reproducible emulator evidence. P3–P7 (relay, BLE,
> Wi-Fi Direct, dynamic routing and real WhatsApp validation) are **not
> implemented**. See [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md).

---

## Download the lab APK

The installable P2 lab build (0.2.0-p2, pre-release) and its test client are
attached to the GitHub release:

- **OpenMesh lab APK + test client:** <https://github.com/wundersoftware-gif/OpenMesh/releases/tag/v0.2.0-p2>

Install both APKs to exercise the P2 evidence on an Android 10+ device or
emulator. This is **not** a working WhatsApp mesh VPN: the debug APK captures
only `com.openmesh.testclient`, there is no relay, and all runtime evidence
comes from emulators on one host. The release APK targets `com.whatsapp` but is
unsigned and unvalidated, so it is not attached to the release. Read the status
below and [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) before
installing.

---

## 1. Status at a glance

| Phase | Scope | Status | Evidence |
|---|---|---|---|
| P0 | Gradle skeleton, VPN consent, TUN, per-app allowlist, capture-only fail-closed, UI state/logs | **Implemented and validated in emulator with a substitute app** | [`docs/evidence/p0-failclosed-2026-11-17.txt`](docs/evidence/p0-failclosed-2026-11-17.txt) |
| P1 | hev-socks5-tunnel, loopback SOCKS5, TCP + DNS-only UDP, lab egress, no leaks/loops | **Implemented and validated in emulator with a substitute app** | [`docs/evidence/p1-testclient-2026-11-17.txt`](docs/evidence/p1-testclient-2026-11-17.txt) |
| P2 | Authenticated remote gateway over TLS 1.3 (A → C → Internet, no relay) | **Implemented and validated emulator-to-emulator with a substitute app** | [`docs/evidence/p2-direct-gateway-2026-11-17.txt`](docs/evidence/p2-direct-gateway-2026-11-17.txt) |
| P3 | Opaque relay / multi-hop | **Not implemented** | — |
| P4 | BLE discovery | **Not implemented** | — |
| P5 | Wi-Fi Direct transport | **Not implemented** | — |
| P6 | Dynamic routing | **Not implemented** | — |
| P7 | Real WhatsApp over A → B → C | **Not run** | Blocked: no physical devices and no WhatsApp account in the emulator |

What is real today:

- A foreground `VpnService` that creates a TUN interface, captures **only one
  allowlisted package**, installs default IPv4/IPv6 routes, and publishes an
  honest multi-dimensional state (link / route / Internet).
- A capture-only mode that **drains and drops** packets: when there is no
  gateway, nothing leaks through the underlying network.
- A lab mode that runs `TUN → hev-socks5-tunnel 2.20.0 → loopback SOCKS5 →
  direct socket`, with a random per-session RFC 1929 credential, a destination
  policy that blocks private/loopback/link-local/CGNAT/multicast addresses, and
  DNS-only UDP.
- **A remote gateway over TLS 1.3**: the gateway proves a self-signed EC P-256
  identity pinned by SHA-256 fingerprint, authenticates the client with an
  RFC 1929 token inside TLS, serves SOCKS5 CONNECT, sends DNS as DNS-over-TCP
  to `1.1.1.1`/`9.9.9.9` through the gateway, and the client only reports
  `Route=SELECTED` / `Internet=VERIFIED` after a real probe
  (`example.com:443`, nested TLS 1.3, HTTP HEAD 2xx/3xx).
- A restricted SOCKS5 server with an exact-match destination policy, a strict,
  bounded binary frame codec (`OMSH` v1), socket-level unit tests and TLS
  gateway tests.
- A Compose UI that never presents a link or an engine as "Internet connected",
  and that fails closed (route NONE, Internet FAILED) when the gateway is
  missing, unpinned or wrongly authenticated.

What is **not** real today:

- There is **no relay node B and no multi-hop path**. P2 is a single gateway
  hop: A dials C directly over TLS and C exits to the Internet.
- There is no mesh discovery (BLE, Wi-Fi Direct) and no dynamic routing.
- No test has ever run against WhatsApp itself, on a physical phone, or on
  `arm64-v8a`. The runtime evidence is from two x86_64 Android 16 emulators on
  one host with a bundled test client and an `adb forward`. This substitution is
  explicit in the UI and in
  [`docs/ADR/0003-debug-target-and-lab-socks.md`](docs/ADR/0003-debug-target-and-lab-socks.md).
- There is no mutual TLS, no Android Keystore protection for the token, no
  quota/revocation UI and no release signing.

The final MVP acceptance criterion from `GOAL_PROMPT.md` — real WhatsApp
messages travelling A → B → C → Internet with A offline — is therefore **not
met**.

---

## 2. Compliance boundaries

OpenMesh:

- uses only public Android APIs (`VpnService`, `addAllowedApplication`,
  `VpnService.protect`, foreground services);
- does **not** modify, automate, inspect, hook or reverse-engineer WhatsApp;
- does **not** use private WhatsApp APIs, protocols or credentials;
- does **not** require root, `iptables`, kernel NAT or a router;
- does **not** use a central transport server;
- does **not** claim that a feature works without reproducible evidence;
- does **not** implement custom cryptography. Future node authentication and
  encryption are planned on top of TLS 1.3 and platform keystores (see
  [`docs/SECURITY.md`](docs/SECURITY.md)).

Using a VPN to route another app is legitimate Android functionality, but
sharing Internet access may be restricted by a mobile carrier's terms. The
gateway must be an explicitly consenting device; OpenMesh is not a tool for
evading captive portals, Wi-Fi authentication or access controls.

---

## 3. Repository layout

```text
.
├── app/                    Compose UI, manifest, application assembly
├── testclient/             Debug-only lab traffic generator (not WhatsApp)
├── core/
│   ├── model/              Pure Kotlin models and typed errors
│   ├── protocol/           OMSH v1 frame model + strict codec
│   └── routing/            Placeholder module (P6)
├── vpn/
│   ├── android/            VpnService, TUN, capture engine, lab frontend
│   └── tun2socks/          Pinned hev-socks5-tunnel AAR + JNI wrapper
├── gateway/                Restricted SOCKS5 server, DNS relay, policy, TLS gateway, tests
├── transport/              TLS 1.3 contexts, node identity, fingerprint pinning
├── relay/                  Placeholder module (P3)
├── discovery/ble/          Placeholder module (P4)
├── tools/test-server.mjs   Host HTTP test server for P1 evidence
├── docs/                   Architecture, protocol, security, test plan, ADRs
├── third_party/            Third-party license texts
└── GOAL_PROMPT.md          Original product specification
```

The proposed `storage/`, `diagnostics/` and separate `core/crypto/` modules do
not exist yet. State is intentionally in-memory until a persistence requirement
is proven; the app must not show a stale "connected" state after process death.

---

## 4. Requirements

Verified toolchain (Windows 11, PowerShell):

| Component | Version |
|---|---|
| JDK for Gradle | 21 (tested with 21.0.7) |
| Gradle wrapper | 9.5.1 |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.03.01 |
| Android SDK platform | 36 (`compileSdk`/`targetSdk` 36) |
| Build tools | 36.0.0 |
| `minSdk` | 29 |
| Android SDK path | `%LOCALAPPDATA%\Android\Sdk` |
| Node.js (test server only) | 18+ |

No Android Studio is required, and no NDK build is performed: the tun2socks
AAR is pinned and prebuilt for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`.

Create `local.properties` if it does not exist (it is machine-specific and must
not be committed):

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

---

## 5. Build and test

From the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'

# Debug app (captures com.openmesh.testclient) and the lab client
.\gradlew.bat :app:assembleDebug :testclient:assembleDebug

# Unit tests (45 tests: frame codec, destination policy, SOCKS5 server,
# tun2socks config, TLS loopback, TLS gateway + pinned client)
.\gradlew.bat test

# Release APK (captures com.whatsapp; unsigned, capture-only)
.\gradlew.bat :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `testclient/build/outputs/apk/debug/testclient-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

`JAVA_HOME` must point to JDK 21. A newer system JDK can make AGP fail before
the build starts.

### Build variants

| Variant | Application ID | Captured package | Data path |
|---|---|---|---|
| `debug` | `com.openmesh.app.debug` | `com.openmesh.testclient` | Direct gateway (P2), lab direct SOCKS (P1) or capture-only (P0); plus the gateway service and the debug control receiver |
| `release` | `com.openmesh.app` | `com.whatsapp` | Direct gateway (P2) if configured, otherwise capture-only. Unsigned and unvalidated |

The debug substitution exists because the emulator has no WhatsApp account and
because validating a VPN path must not require automating WhatsApp. It proves
capture mechanics, not WhatsApp behaviour. The UI displays the substitution on
the target card.

---

## 6. Run P0/P1/P2 on an emulator

These are the exact steps used for the evidence in `docs/evidence/`.

### 6.1 Prepare an emulator

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"

& "$sdk\emulator\emulator.exe" -avd openmesh_test -no-window -no-audio `
    -gpu swiftshader_indirect
& $adb wait-for-device
```

The evidence used AVD `openmesh_test`: x86_64, Android 16 (API 36), build
fingerprint `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.

### 6.2 Install

```powershell
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r testclient\build\outputs\apk\debug\testclient-debug.apk
```

Normal users grant VPN consent through the system dialog. For unattended lab
runs only, the consent can be pre-granted:

```powershell
& $adb shell appops set com.openmesh.app.debug ACTIVATE_VPN allow
& $adb shell pm grant com.openmesh.app.debug android.permission.POST_NOTIFICATIONS
```

### 6.3 Start the host test servers

Two listeners are used so the destination policy can be tested against a live
service that must be denied:

```powershell
# Terminal 1: allowlisted lab destination
node tools/test-server.mjs

# Terminal 2: decoy that is reachable from the emulator but not allowlisted
$env:OPENMESH_TEST_PORT = '8081'; node tools/test-server.mjs
```

The emulator reaches the host as `10.0.2.2`.

### 6.4 P1 lab run

1. Open **OpenMesh** on the emulator.
2. Leave **Lab direct SOCKS (P1)** enabled.
3. Tap **Start lab tunnel (P1)** and accept the VPN consent if asked.
4. Confirm `TUNNEL STATUS: Tunnel ready`, `Link: UP`, `Route: NONE`,
   `Internet: UNKNOWN`.
5. Launch the test client:

   ```powershell
   & $adb shell am start -n com.openmesh.testclient/.MainActivity --ez autoStart true
   ```

6. Expected result:
   - DNS for `example.com` succeeds.
   - `http://10.0.2.2:8080/health` returns HTTP 200.
   - `https://example.com/` returns HTTP 200 with a TLS 1.3 cipher.
   - `http://10.0.2.2:8081/health` is **denied** even though a listener is
     running.
   - OpenMesh counters increase (TX and RX); engine is `tun2socks`.
   - `Route` stays `NONE` and `Internet` stays `UNKNOWN` — this is a lab path,
     not a gateway.

### 6.5 P2 direct-gateway run (two emulators)

P2 needs one emulator to be the gateway and another to be the client. The
evidence used `emulator-5554` (gateway, AVD `openmesh_test`) and `emulator-5556`
(client, AVD `vp_test`), with an `adb forward` standing in for a LAN.

```powershell
& "$sdk\emulator\emulator.exe" -avd vp_test -port 5556 -no-window -no-audio `
    -gpu swiftshader_indirect
& $adb -s emulator-5554 forward tcp:8443 tcp:8443
```

1. On the gateway emulator, start the gateway (UI card **Gateway (this phone)**
   → **Start gateway**, or for unattended runs):

   ```powershell
   & $adb -s emulator-5554 shell am broadcast -a com.openmesh.debug.START_GATEWAY `
     --ei port 8443 -n com.openmesh.app.debug/com.openmesh.app.DebugControlReceiver
   & $adb -s emulator-5554 logcat -d -s OpenMeshGateway
   # gateway listening on 0.0.0.0:8443 fingerprint=...
   ```

2. On the client emulator, grant the VPN consent, then start the VPN in gateway
   mode with the fingerprint and the token from the gateway UI:

   ```powershell
   & $adb -s emulator-5556 shell appops set com.openmesh.app.debug ACTIVATE_VPN allow
   & $adb -s emulator-5556 shell am broadcast -a com.openmesh.debug.START_VPN `
     --es mode gateway --es host 10.0.2.2 --ei port 8443 `
     --es fingerprint <64 hex> --es token <gateway token> `
     -n com.openmesh.app.debug/com.openmesh.app.DebugControlReceiver
   ```

   The gateway token is readable in the lab with
   `adb -s emulator-5554 shell run-as com.openmesh.app.debug cat shared_prefs/openmesh_gateway.xml`.
   On real phones it is copied out of band; the adb route is a lab shortcut
   only.

3. Run the test client on the client emulator and confirm in the OpenMesh UI:
   `Link: UP`, `Route: SELECTED`, `Internet: VERIFIED`, `gatewayId=10.0.2.2:8443`,
   `hops=1`.

Expected result (see
[`docs/evidence/p2-direct-gateway-2026-11-17.txt`](docs/evidence/p2-direct-gateway-2026-11-17.txt)):

- DNS for `example.com` resolves (DNS-over-TCP through the gateway).
- `http://10.0.2.2:8080/health` → HTTP 200.
- `https://example.com/` → HTTP 200 with `TLS_AES_128_GCM_SHA256` (a nested TLS
  1.3 session inside the gateway TLS session).
- `http://10.0.2.2:8081/health` is denied by the gateway destination policy.

Negative runs, all fail closed:

| Fault | Observed |
|---|---|
| Gateway stopped | `Route=NONE`, `Internet=FAILED`, `gateway unreachable`, every fetch fails |
| Wrong token | `Socks5Exception: SOCKS5 authentication failed`, gateway `denied` counter increases |
| Wrong fingerprint | `gateway certificate fingerprint mismatch; expected ...0001, got 4667...7ed2` |

### 6.6 P0 fail-closed run

1. Stop the VPN.
2. Select **Capture only (P0)**.
3. Tap **Start capture-only VPN (P0)**.
4. Run the test client again.
5. Expected result:
   - DNS fails.
   - HTTP to `10.0.2.2:8080` times out from source `10.111.0.2`.
   - HTTPS fails at DNS.
   - Neither host listener receives a request.
   - OpenMesh shows `Capturing · fail-closed`, TX > 0 and **RX = 0**.

### 6.7 Chrome outside the VPN

While the VPN is active, open Chrome and browse to `https://example.org/`.
Chrome is not in the allowlist and must keep normal connectivity. This was
verified; see `docs/evidence/p1-chrome-outside-vpn.png`.

### 6.8 Stop and reset

Tap **Stop VPN**. The TUN disappears, Link becomes `DOWN`, Route `NONE`,
Internet `UNKNOWN`, and all session counters return to zero. The foreground
notification is removed.

---

## 7. Safety notes

- The release build is **not usable for real WhatsApp traffic yet**. It targets
  `com.whatsapp`, but without a configured gateway it is capture-only and drops
  everything WhatsApp sends. Do not install it expecting messages to work.
- The debug build captures only `com.openmesh.testclient`; it does not and
  cannot capture WhatsApp.
- The P2 gateway listens on `0.0.0.0:8443`. It requires TLS 1.3 with a pinned
  certificate and a per-gateway token, and it applies the destination policy,
  but it is **not hardened against a hostile network**: no mutual TLS, no
  quotas, no revocation, no fuzzing. Expose it only on a network you trust,
  and stop it when you do not need it.
- The gateway token and the gateway private key are stored as app-private data
  (SharedPreferences and a PKCS#12 file), not in the Android Keystore.
- Do not add a lab allowlist entry to a release build. The debug allowlist is
  exactly `10.0.2.2:8080`.
- OpenMesh logs metadata only. It must never log message content, contacts,
  DNS question names, credentials or URLs.

---

## 8. Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — implemented architecture and
  the P2/P3 design boundary.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — OMSH v1 wire format, validation and
  planned state machines.
- [`docs/SECURITY.md`](docs/SECURITY.md) — threat model, implemented controls
  and residual risks.
- [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) — test matrix, evidence and blocked
  tests.
- [`docs/ADR/`](docs/ADR/) — technical decisions.
- [`CHANGELOG.md`](CHANGELOG.md) — changes in this version.
- [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) — phase-by-phase
  status and next steps.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — licenses and pinned
  artifacts.

---

## 9. Third-party components

The only native component is **hev-socks5-tunnel 2.20.0** (MIT), pinned as
`vpn/tun2socks/libs/hev-socks5-tunnel-2.20.0.aar`
(SHA-256 `AE8A2E96B80B4ECCCCD037805A4BB4A278090C89DBCAE8BC6FCB7634E9A255B9`).
AndroidX, Kotlin, Kotlin Coroutines and Bouncy Castle are Apache-2.0 or
equivalent permissive licenses. JUnit is used for tests only. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

OpenMesh's own code is not yet published under a license file; add one before
distributing binaries.

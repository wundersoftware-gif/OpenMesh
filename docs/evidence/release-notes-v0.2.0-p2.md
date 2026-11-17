OpenMesh **0.2.0-p2** — direct gateway over TLS 1.3 (lab build).

This release adds the **P2 data path**: a phone can tunnel one allowlisted app
through another phone that acts as a gateway, over an authenticated TLS 1.3
session. This is a **single gateway hop (A → C → Internet)** — there is no
relay, no mesh, no BLE and no Wi-Fi Direct yet.

## What works (emulator evidence)

Two Android 16 / API 36 emulators on one host, gateway + client, joined by an
`adb forward`:

- DNS resolves through the gateway (DNS-over-TCP inside the TLS session; no
  plaintext DNS leaves the device — verified with `tcpdump -i any`).
- `http://10.0.2.2:8080/health` → **HTTP 200**, 206 bytes, through the gateway.
- `https://example.com/` → **HTTP 200**, 577 bytes, `TLS_AES_128_GCM_SHA256`
  (a nested TLS 1.3 session inside the gateway TLS session).
- Client state: `phase=TUNNEL_READY, link=UP, route=SELECTED,
  internet=VERIFIED, gatewayId=10.0.2.2:8443, hops=1`.
- Fail-closed, proven three ways: gateway stopped → `NO_ROUTE` and every fetch
  fails; wrong token → `SOCKS5 authentication failed`; wrong certificate
  fingerprint → handshake aborted. No traffic leaks in any of these cases.

Full transcript: `docs/evidence/p2-direct-gateway-2026-11-17.txt`.

## What is NOT in this release

- **No relay / no multi-hop.** P2 is one hop. A → B → C is not implemented.
- **No WhatsApp.** The debug APK captures `com.openmesh.testclient` only. The
  release-targeting APK (`com.whatsapp`) is unsigned and **deliberately not
  attached**: it has never been run, and WhatsApp traffic on a phone without a
  configured gateway is dropped fail-closed.
- **No physical-phone or arm64 validation.** Everything here is two x86_64
  emulators on one host.
- **No signing.** These are debug-signed APKs, for lab use only.
- No mutual TLS, no Android Keystore storage for the gateway token, no quotas,
  no revocation UI, no fuzzing of the gateway listener.

## Artifacts

| File | SHA-256 |
|---|---|
| `app-debug.apk` (0.2.0-p2-debug, `com.openmesh.app.debug`) | `97be392b90eb8c8a6855a2a13daf5fc4a393f5108b74b92a19e552df9b28984e` |
| `testclient-debug.apk` (0.2.0-p2, `com.openmesh.testclient`) | `00e95eb35734c52e1b70d8a2b1ac2071a4dadd0da3e7435121f0f6e23b3605e9` |

The debug APK bundles all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`,
`x86_64`) and the pinned `hev-socks5-tunnel 2.20.0` AAR
(SHA-256 `AE8A2E96B80B4ECCCCD037805A4BB4A278090C89DBCAE8BC6FCB7634E9A255B9`).

## How to run it

Two devices on the same network (no `adb forward` needed on real phones):

1. Install `app-debug.apk` on both; grant VPN consent when asked.
2. On the gateway phone, open OpenMesh → **Direct gateway (P2)** → start the
   gateway. Note the **SHA-256 fingerprint** and the **token**.
3. On the client phone, select **Direct gateway (P2)**, enter the gateway
   address, port `8443`, the fingerprint and the token, then start the VPN.
4. The UI shows Link / Route / Internet independently; `Route=SELECTED` and
   `Internet=VERIFIED` appear only after a real probe through the gateway.
5. Stop the gateway and watch everything fall back to `NO_ROUTE` with no leak.

Detailed steps: README §6.5.

## Security notes

- The gateway listens on `0.0.0.0:8443`. It requires TLS 1.3 with a pinned
  certificate plus a per-gateway token, and it refuses private/loopback/
  link-local destinations, but it has **not** been fuzzed or exposed to a
  hostile network. Run it only on a network you trust, and stop it when you
  are done.
- The gateway private key is a PKCS#12 file in app-private storage and the
  token is in app-private preferences; neither is Android-Keystore backed.
- This build is a lab prototype, not a privacy tool. Read
  `docs/SECURITY.md` before exposing anything.

Source: https://github.com/wundersoftware-gif/OpenMesh
Status: https://github.com/wundersoftware-gif/OpenMesh/blob/main/IMPLEMENTATION_STATUS.md
OpenMesh **0.2.0-p2 — WhatsApp lab build**.

> ## Read this before installing
>
> This APK **captures `com.whatsapp` and nothing else**. If no OpenMesh gateway
> is reachable, WhatsApp has **no connectivity at all** — its traffic is
> captured and dropped, which is the intended fail-closed behaviour.
>
> It is **debug-signed**, versioned `0.2.0-p2-debug-whatsapp`, and it exists
> only to reproduce the WhatsApp lab evidence. **Do not install it on a phone
> you depend on.** It is not a product, not a working WhatsApp mesh VPN, and it
> carries no promise that WhatsApp messages work.

This is a variant of the `v0.2.0-p2` lab build, not a new data path. The only
functional difference is **IPv4-only egress**:

- the P2 client answers single-question AAAA DNS queries with an empty NOERROR
  response (`DnsQueryFilter`), so Android hands out IPv4 addresses;
- `::/0` is still routed into the TUN, so IPv6 cannot leak to the underlying
  network; it is captured, not carried.

That fix is what allowed real WhatsApp to connect: before it, WhatsApp
preferred IPv6, hit the black-holed TUN and reported
`Connection reset by peer` / `SecureTCP connection establish timeout`.

## What was observed on 2026-11-18

Redmi 13C (HyperOS 2.0 / Android 15, `arm64-v8a`), real registered WhatsApp,
only `com.whatsapp` in the allowlist:

| Run | Gateway | WhatsApp log |
|---|---|---|
| A | loopback on the same phone, `127.0.0.1:8443` | `g.whatsapp.net:443` → `result=ready`, `connecting=>connected`, `connectedIPVersion=4` |
| B | emulator, via `adb reverse`/`adb forward` | `g.whatsapp.net:5222` → `result=ready`, `connecting=>connected`, `connectedIPVersion=4`, then a clean `Received EOF` after 76 s |

Gateway counters in Run B: `accepted=13, denied=16, bytesToRemote=21811,
bytesFromRemote=52585`.

**What this does not show:** that a message, notification or media file was
delivered. No message was sent or received; the screen was locked; no reconnect
was observed after the EOF. `denied=16` is unexplained (the audit sink is a
no-op). There is still no relay and no run with two physical phones.

Full transcript: `docs/evidence/whatsapp-p2-2026-11-18.txt`.
Gateway-side capture: `docs/evidence/whatsapp-p2-2026-11-18.pcap`.

## Artifact

| File | SHA-256 | Size |
|---|---|---|
| `OpenMesh-0.2.0-p2-whatsapp-debug.apk` | `09209178FC46692BBA6CEEB140ACFC55FCF5FDFB3B08A3996AE86345DF873322` | 39 053 470 B |

Application id `com.openmesh.app.debug`, `versionCode` 2. Debug-signed with the
standard Android debug key; no signing keystore is used for this project yet.

## Install

```text
adb install -r -t OpenMesh-0.2.0-p2-whatsapp-debug.apk
```

It replaces the `com.openmesh.testclient` lab build on the same device, because
both use `com.openmesh.app.debug`.

To exercise it, start a P2 gateway on a second Android instance and configure
the client with that gateway's fingerprint and token (the debug control
receiver accepts `am broadcast -a com.openmesh.debug.START_VPN ...`, see the
repository README §6.5). Without a reachable gateway, WhatsApp will simply have
no connectivity.

## Boundaries

- No root, no WhatsApp modification, no private APIs, no automation of WhatsApp.
- TLS 1.3 with a pinned self-signed gateway certificate and an RFC 1929 token
  inside TLS; no custom cryptography.
- Single gateway hop (A → C). No relay B, no BLE, no Wi-Fi Direct, no dynamic
  routing, no IPv6 egress.
- Mutual TLS, Keystore-backed secrets, quotas and revocation are still missing.

Source, docs and the P2 lab release:
<https://github.com/wundersoftware-gif/OpenMesh>

# ADR 0005 — OMSH v1 binary framing

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** OpenMesh implementation team

## Context

The mesh needs a versioned, binary, bounded frame format that can carry control
messages and stream data between authenticated nodes. It will eventually be
carried over TCP + TLS 1.3, but the frame contract must not depend on the
transport, so QUIC can replace it later without changing the VPN or SOCKS
interfaces.

The format must resist malformed input, avoid JSON per packet, prevent unbounded
allocation, and allow a relay to forward frames without understanding
application payloads. The header needs a routing identifier that is smaller
than a full public key but large enough to avoid collisions in a small mesh.

`GOAL_PROMPT.md` proposes a 36-byte header with magic, version, type, flags,
stream ID, 16-byte destination ID, and a 4-byte payload length. That is already
a sound starting point.

## Decision

Adopt **OMSH v1**, exactly as specified in `docs/PROTOCOL.md`:

- Magic `OMSH` (4 bytes), version `1`, 1-byte type, 2-byte flags.
- 8-byte non-negative stream ID; `0` is the control stream.
- 16-byte raw destination ID, represented in Kotlin as 32 lowercase hex chars.
- 4-byte payload length, maximum **65,536 bytes**.
- All integers big-endian.
- Header size **36 bytes**; maximum frame **65,572 bytes**.
- Strict validation: unknown version/type/flags, bad magic, negative stream ID,
  oversized length, truncation and trailing bytes are all errors.
- Payload layouts for `OPEN_STREAM`, `OPEN_RESULT`, `STREAM_DATA`,
  `WINDOW_UPDATE`, `CLOSE_STREAM`, `ERROR`, `PING` and `PONG` are documented as
  the v1 target; `HELLO`/`AUTH`/routing payloads remain to be specified with
  the transport phase.
- The destination ID in the header is the **routing** destination, not the
  Internet host. The host/port of a proxied stream lives inside the encrypted
  `OPEN_STREAM` payload, invisible to relays.

## Alternatives considered

| Alternative | Why not |
|---|---|
| JSON per packet | Verbose, ambiguous numbers, parsing attacks, poor fit for a data path |
| Protobuf/CBOR | Adds a schema/compiler dependency and still needs a length bound; a fixed header is simpler to audit |
| Length-prefix only, no type/stream fields | Insufficient for multiplexing and control messages |
| 32-byte destination ID (full hash) | Header grows; 16 bytes is enough for a small mesh and matches the stated requirement |
| Dynamic/negotiated header size | More states and attack surface before the transport exists |
| 1 MiB or unbounded payload | Memory exhaustion and head-of-line blocking; 64 KiB is a good stream chunk |

## Consequences

- `core:protocol` is pure Kotlin and runs on the JVM; 17 tests cover the codec.
- The 64 KiB limit requires an upper layer to split application writes into
  multiple `STREAM_DATA` frames; this is normal and keeps buffers bounded.
- The header is frozen for v1. Adding fields requires a version bump or an
  extension mechanism, not a silent layout change.
- The frame codec is currently not connected to any transport. Its existence
  must not be presented as a working mesh protocol.
- Future TLS/relay code must preserve the rule that relays cannot see
  `OPEN_STREAM` host/port or payload bytes.

## Verification

- `FrameCodecTest`: round-trip, max payload, bad magic, unknown version/type/
  flags, oversized and truncated lengths, trailing bytes, clean EOF, invalid
  node ID, stream reads.
- `docs/PROTOCOL.md` is the normative description.
- No interoperability test exists yet because there is no second implementation
  or transport.

## References

- `GOAL_PROMPT.md` §6
- `core/protocol/src/main/kotlin/com/openmesh/core/protocol/`
- `docs/PROTOCOL.md`

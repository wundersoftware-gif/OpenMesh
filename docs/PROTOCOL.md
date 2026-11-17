# OpenMesh Protocol (OMSH) v1

This document specifies the binary framing that OpenMesh uses between mesh
nodes, plus the stream semantics that the transport must provide.

> **Implementation status**
>
> The **frame model and codec are implemented** in `core:protocol`
> (`MeshFrame`, `FrameType`, `FrameCodec`) and covered by 17 JVM tests.
> **No transport, handshake, routing or stream multiplexer is implemented
> yet.** Sections marked *planned* are the contract for P2/P3, not current
> behaviour. Do not describe them as working features.

The key words MUST, MUST NOT, SHOULD and MAY are used in the RFC 2119 sense.

---

## 1. Design rules

1. Binary, not JSON, for every data frame.
2. All integers are **big-endian** (network byte order).
3. The header is fixed-size and fully validated before any allocation.
4. No field length is trusted. The declared payload length is checked against
   a hard maximum before memory is allocated.
5. Unknown versions, frame types and flags are **errors**, never silently
   ignored.
6. A frame belongs to one logical stream. Stream `0` is reserved for
   connection-level control.
7. Application payloads are end-to-end encrypted between the client and the
   gateway. Relays must not be able to parse them.
8. No custom cryptography. Identity, confidentiality and integrity are
   provided by TLS 1.3 and platform keystores.

---

## 2. Frame format

```text
 offset  size  field             encoding / value
 ------  ----  ----------------  -------------------------------------------
 0       4     magic             ASCII "OMSH" = 4F 4D 53 48
 4       1     protocol version  0x01
 5       1     message type      see section 3
 6       2     flags             bit field, see section 4
 8       8     stream id         unsigned 63-bit value (high bit MUST be 0)
 16      16    destination id    raw 16-byte routing identifier
 32      4     payload length    0 .. 65536 (64 KiB)
 36      N     payload           application/control bytes
```

- **Header size:** 36 bytes.
- **Maximum payload:** 65,536 bytes.
- **Maximum frame size:** 36 + 65,536 = 65,572 bytes.
- **Destination ID:** a 16-byte routing identifier. In Kotlin it is carried as
  exactly 32 lowercase hexadecimal characters; decoding always produces
  lowercase. All-zero (`00000000000000000000000000000000`) is the reserved
  null destination for connection-level control frames.
- **Stream ID:** `0` is the control stream. Data streams use values `>= 1`.
  A negative signed `Long` is invalid (the top bit is reserved).

The destination ID identifies the **next routing destination or final gateway**
for the frame; it does **not** contain an Internet hostname or IP address. The
final destination of a proxied TCP stream is carried inside the encrypted
`OPEN_STREAM` payload so relays cannot read it.

### 2.1 Encoding example

A `PING` on the control stream with the null destination is exactly 36 bytes:

```text
4F 4D 53 48  01  0A  00 00  00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00
```

---

## 3. Message types

Numeric values are part of the wire format and MUST NOT be renumbered. New
types may be appended after `0x0C`.

| Code | Type | Purpose | Payload status |
|---|---|---|---|
| `0x01` | `HELLO` | Connection-level introduction and capability negotiation | Planned |
| `0x02` | `AUTH` | Authorization/consent of an already TLS-authenticated peer | Planned |
| `0x03` | `PEER_ANNOUNCE` | Neighbour and capability announcement | Planned |
| `0x04` | `ROUTE_UPDATE` | Route cost/expiry update | Planned |
| `0x05` | `OPEN_STREAM` | Request a new outbound TCP stream | Planned |
| `0x06` | `OPEN_RESULT` | Accept/refuse the stream | Planned |
| `0x07` | `STREAM_DATA` | Opaque stream bytes | Planned |
| `0x08` | `WINDOW_UPDATE` | Flow-control credit | Planned |
| `0x09` | `CLOSE_STREAM` | Graceful close or reset | Planned |
| `0x0A` | `PING` | Liveness / RTT probe | Planned |
| `0x0B` | `PONG` | Liveness reply | Planned |
| `0x0C` | `ERROR` | Recoverable or fatal protocol error | Planned |

`OPEN_RESULT` was added to `GOAL_PROMPT.md`'s initial list so a client can
distinguish "request accepted" from "SOCKS request sent". The codec already
implements all 12 values.

### 3.1 Control frames

Control frames use `streamId = 0` and the null destination unless routing
metadata requires otherwise. They are not subject to stream flow control.

### 3.2 Planned payload contracts (version 1 target)

These layouts are the design contract for P2/P3. They are **not encoded or
parsed anywhere yet**; changing them before implementation does not break
compatibility. Once implemented, they become stable.

```text
OPEN_STREAM (sent inside the client↔gateway encrypted channel)
  1 byte   address type: 0x01 IPv4, 0x03 domain, 0x04 IPv6
  1 byte   address length (0 for IPv4/IPv6, 1..255 for domain)
  N bytes  address bytes (ASCII domain, or 4/16 raw bytes)
  2 bytes  destination port (1..65535)

OPEN_RESULT
  1 byte   status: 0 ok, 1 denied, 2 host unreachable, 3 timeout,
           4 connection refused, 5 bad request
  1 byte   address type of the bound address (optional, 0 if absent)
  N bytes  bound address (optional)
  2 bytes  bound port (optional, 0 if absent)

STREAM_DATA
  N bytes  opaque application bytes, 0..65536

WINDOW_UPDATE
  4 bytes  unsigned credit increment in bytes (1..2^31-1)

CLOSE_STREAM
  1 byte   reason: 0 normal, 1 reset, 2 timeout, 3 policy, 4 protocol error

ERROR
  2 bytes  error code
  N bytes  UTF-8 diagnostic, bounded to 1024 bytes, no payload or PII

PING / PONG
  8 bytes  opaque nonce chosen by the sender (echoed by PONG)
```

`HELLO`, `AUTH`, `PEER_ANNOUNCE` and `ROUTE_UPDATE` payloads will be specified
together with the transport and routing phases. They MUST NOT be guessed by
intermediaries in the meantime.

---

## 4. Flags

| Bit | Name | Meaning |
|---|---|---|
| `0` (`0x0001`) | `FLAG_ACK_REQUIRED` | Sender requests an acknowledgement at the stream layer |
| `1` (`0x0002`) | `FLAG_FIN` | Half-close: no more data from this sender |
| `2` (`0x0004`) | `FLAG_RST` | Abort the stream immediately |

All other bits are reserved and MUST be zero. The codec rejects frames with
unknown flags on both encode and decode. `FLAG_FIN` and `FLAG_RST` MUST NOT be
set together.

---

## 5. Codec validation rules (implemented)

`FrameCodec.decode(ByteArray)` and `FrameCodec.read(InputStream)` enforce:

1. At least 36 bytes for a frame.
2. Magic equals `OMSH`.
3. Version equals `1`.
4. Type is a known numeric value.
5. No unknown flag bits.
6. `streamId >= 0`.
7. Payload length in `0..65536`.
8. For `decode(ByteArray)`: the remaining bytes equal exactly the declared
   payload length (no trailing bytes).
9. For `read(InputStream)`: a clean EOF at a frame boundary returns `null`; a
   truncated header or payload throws `EOFException`. A truncated frame is
   never silently accepted.
10. Destination IDs must be exactly 32 hex characters on encode. `decode`
    always emits lowercase hex.
11. `MeshFrame` itself refuses a payload larger than 64 KiB and a negative
    stream ID.

All failures are `ProtocolException` (an `IOException`) except construction
`IllegalArgumentException` for invalid `MeshFrame` arguments and
`EOFException` for truncation.

### 5.1 Deliberate non-goals

- No compression (avoids compression side channels and unbounded expansion).
- No JSON, XML or text framing on the data path.
- No fragmentation at the frame layer: an upper layer must split application
  data into `<= 64 KiB` `STREAM_DATA` frames.
- No payload inspection by relays.

---

## 6. Stream semantics (planned)

### 6.1 Lifecycle

```text
IDLE ──OPEN_STREAM──► OPENING ──OPEN_RESULT ok──► OPEN
                            └──OPEN_RESULT error──► CLOSED
OPEN ──STREAM_DATA──► OPEN (bidirectional)
OPEN ──FLAG_FIN─────► HALF_CLOSED_LOCAL / HALF_CLOSED_REMOTE
both FIN ───────────► CLOSED
any ──FLAG_RST──────► CLOSED (abort)
```

- Stream IDs are allocated by the stream initiator per transport connection,
  monotonically increasing, never reused within that connection.
- A peer MUST reject `OPEN_STREAM` with an ID already in use, zero, or negative.
- `CLOSE_STREAM` without `FLAG_RST` is a graceful close. `FLAG_RST` discards
  buffered data and releases resources immediately.
- After `CLOSE_STREAM`, the ID enters a short quarantine so late frames cannot
  be mistaken for a new stream.

### 6.2 Flow control and backpressure

- Initial receive window: **256 KiB per stream** (proposed; configurable).
- Hard maximum receive window: **4 MiB per stream**.
- The receiver grants credit only when it has actually drained bytes; it MUST
  NOT grant credit merely on receipt.
- `WINDOW_UPDATE.payload` is a **credit increment**, not an absolute value.
  A receiver MUST reject a zero or oversized increment and MUST detect
  overflow of the sender's credit counter.
- The sender MUST NOT send `STREAM_DATA` beyond the current credit. If the
  application produces faster than credit is available, the sender applies
  backpressure to the SOCKS socket (stops reading) instead of buffering
  without bound.
- Each transport connection also has a global byte budget and a maximum number
  of open streams (proposed: 256). Exceeding either is a recoverable error and
  new streams are refused.

### 6.3 Timeouts (proposed)

| Event | Timeout | Action |
|---|---|---|
| TLS handshake | 10 s | Close connection |
| `HELLO`/`AUTH` | 10 s | Close connection |
| `OPEN_STREAM` → `OPEN_RESULT` | 15 s | Return SOCKS failure, close stream |
| Idle stream (no data/credit either direction) | 300 s | `FLAG_RST`, close stream |
| `PING` interval | 30 s | Send probe |
| `PONG` deadline | 10 s | Tear down the connection |
| Graceful shutdown drain | 5 s | Force close |

These are target values, not current behaviour. They must be configurable and
measured before being presented as guarantees.

### 6.4 Buffer limits

- Per-stream send buffer: bounded by the receive window (max 4 MiB).
- Per-connection memory budget: proposed 16 MiB across all streams.
- SOCKS5 handshake buffers: `Socks5Server` already limits domains to 255 bytes
  and applies socket timeouts.
- Relay queues: apply backpressure/drop rather than grow without bound; an
  overflowing relay closes the stream with `FLAG_RST`.

---

## 7. Errors and recovery

| Class | Examples | Behaviour |
|---|---|---|
| Recoverable | Destination denied, host unreachable, stream refused, window overflow on one stream | `OPEN_RESULT` error or `ERROR` for that stream; connection stays usable |
| Fatal | Bad magic, unknown version, unknown frame type, invalid length, TLS/auth failure, repeated protocol violations | Close the transport connection, count the event, no retry loop without backoff |
| Local | No gateway, TUN gone, service stopped | Stop the VPN path, surface `ERROR`/`NO_ROUTE`, never fall back to a direct leak |

Every fatal error MUST be visible in the UI/diagnostics as metadata. Error
messages MUST NOT include payload bytes, domain names or credentials.

Retries use exponential backoff with jitter and a cap. A node that repeatedly
violates framing is refused rather than retried indefinitely.

---

## 8. Security properties

### 8.1 Transport identity

- TLS 1.3 only; no fallback to older protocol versions.
- Mutual authentication: each node presents a certificate bound to its node
  identity. An unauthenticated peer is rejected before any `HELLO`/`AUTH`.
- Certificate rotation and revocation are planned; the current build has no
  identity implementation at all.

### 8.2 End-to-end channel

- The client and gateway establish an additional logical encrypted channel
  inside the outer TLS connection. Relays forward its ciphertext.
- Relays see only the routing header (destination ID, stream ID, sizes,
  timing), never `OPEN_STREAM` host/port or `STREAM_DATA`.
- This inner channel is a P2 design item. No custom cipher is permitted; use
  the platform TLS stack or a reviewed library.

### 8.3 Replay

- TLS 1.3 record sequence numbers prevent replay within a connection.
- A replayed outer connection fails the TLS handshake because of fresh
  ephemeral key shares.
- The planned `AUTH`/`HELLO` exchange includes a fresh nonce and a short
  timestamp window so an old authenticated session cannot be replayed.
- Stream IDs are scoped to a transport connection and never reused.
- No application-level nonce scheme is implemented yet. Do not claim replay
  protection in P0/P1; in P0/P1 there is no mesh transport to replay.

### 8.4 What is deliberately absent

- No home-grown encryption, key exchange, signature scheme or RNG.
- No bearer tokens or shared passwords in frames.
- No fallback to plaintext if TLS fails.

---

## 9. Versioning and compatibility

- `version = 1` is the only accepted version. A peer that receives another
  version MUST close the connection with a fatal error.
- New message types may be appended; old peers reject them, so a version bump
  or explicit negotiation is required before using them in a mixed network.
- Unknown flags are rejected. Do not use spare flag bits for speculative
  features.
- There is no negotiation yet. When `HELLO` is implemented, it will advertise
  `minVersion`/`maxVersion` and capabilities; the connection uses the highest
  common version, and a mismatch fails closed.
- The frame header is frozen at 36 bytes for version 1.

---

## 10. Implementation status

| Item | Status |
|---|---|
| Frame model, codes, flags, limits | **Implemented** (`core:protocol`) |
| Strict bounded encode/decode + stream reads | **Implemented**, 17 unit tests |
| Physical/logical transport | Not implemented |
| TLS 1.3 mutual authentication | Not implemented |
| End-to-end inner channel | Not implemented |
| `HELLO`/`AUTH`/routing payloads | Not implemented |
| Stream multiplexer, flow control, timeouts | Not implemented |
| Relay forwarding | Not implemented |
| Replay protection | Not implemented (TLS-dependent, P2) |

Until the transport exists, OMSH frames are a tested codec and a written
contract, not a working network protocol.

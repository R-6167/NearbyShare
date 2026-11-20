# WarPair Design Document

**Offline, peer-to-peer nearby file sharing foundation**  
Product name: **WarPair** · Android id: `com.crypticsystems.warpair`  
Rust core + native platform bindings (Android first, iOS later). No cloud. Ever.

> Historical crate path: `crates/nearby-share`. Native library: `libnearby_share.so` (ABI name kept stable).

See also [WARPAIR.md](WARPAIR.md) for packaging and SoftAP / QR notes.

---

## Goals

- Transfer files between nearby devices with no internet
- Strong cryptography (X25519, ChaCha20-Poly1305, BLAKE3)
- PIN confirm before data
- Multiple mediums: Wi-Fi LAN, SoftAP, Wi-Fi Direct, Bluetooth
- Streaming disk I/O with resume
- Keep one logical transfer and its verified checkpoint when the underlying transport changes

## Stack

| Layer | Tech |
|-------|------|
| Core | Rust workspace crate `nearby-share` |
| FFI | C ABI (JNA) + UniFFI path |
| Android | Kotlin app `com.crypticsystems.warpair` |
| Discovery | mDNS `_warpair._tcp`, SoftAP + QR, Wi-Fi Direct and Bluetooth discovery |

## Protocol sketch

Protocol v3: Hello → PIN confirm → BatchStart (stable logical batch ID + expected file count) → receiver `batch_ready` acknowledgement → Offer (stable per-file identity) → Response (optional `resume_bytes`) → Chunk* → per-file Complete / Cancel → BatchComplete (must repeat the same batch ID and file count). The receiver rejects offers before BatchStart, excess files, mismatched completion identity/count, and missing terminal markers. Older protocol-v2 peers are rejected during the authenticated handshake. The sender emits `BatchComplete` only after all offered files reach a terminal result; the receiver rejects a missing marker, a mismatched file count, or a batch that ends via socket close. Older protocol-v1 peers are rejected during the authenticated handshake rather than being allowed to report ambiguous batch success.

A transport reconnect creates a fresh authenticated channel and PIN handshake. It must not silently reset the file checkpoint. The receiver validates the partial file, expected size, sequence boundary and final BLAKE3 checksum before committing the completed file.

## Transport strategy

- Shared Wi-Fi / SoftAP use the existing TCP transport.
- Wi-Fi Direct group negotiation and TCP socket adapter are implemented in `WifiDirectLink`; the group owner accepts a TCP peer and the group client connects to the owner. The caller runs the same authenticated `LanLink` protocol over the returned `TransferConnection`.
- Bluetooth Classic RFCOMM uses a secure, paired-device socket adapter; it is a fallback / small-file option, not assumed to be available or faster.
- The policy layer orders only platform-confirmed available transports. An adapter must still establish a real connection before it is considered usable.
- If a route fails, the coordinator advances to the next available route while retaining the same selected files and transfer checkpoint.
- Never delete a partial file just because a socket or radio link failed.

## Implementation status

- Implemented: crypto, session, codec, Android LAN TCP, SoftAP + QR bootstrap, mDNS discovery, chunk integrity, partial-file resume, and explicit protocol-v3 batch start/completion framing.
- Added in Phase 3 foundation: transport ordering policy, immutable transfer recovery snapshots and deterministic failover sequence with unit tests.
- Added in this phase: a tested transport-failover coordinator that carries forward only validated durable checkpoints, prevents transfer identity/file-list changes, and stops rather than switching routes when the user cancels. Android transport connection adapters and live transfer callbacks are not yet wired into this coordinator; end-to-end automatic switching remains the next integration step.
- Implemented: native Wi-Fi Direct adapter and secure Bluetooth Classic RFCOMM socket adapter for already-paired devices. Bluetooth peer discovery and host/join controls are now wired into the Android UI; Android system pairing is used when a selected device is not already bonded. The transport-independent recovery coordinator is implemented; Android end-to-end automatic failover across radios remains pending.
- Implemented in this phase: native Wi-Fi Direct discovery, peer listing, group create/connect/remove, connection-info reporting and group-owner TCP socket setup; Wi-Fi Direct UI and LanLink orchestration; Bluetooth Classic secure RFCOMM host/connect adapter for bonded peers. Wiring the recovery coordinator to Android connection adapters and device-level interruption/resume tests remain next. Bluetooth discovery and transfer controls are implemented, but require two-phone validation across Android versions.
- Added stable per-file offer IDs to each selected `OutgoingFile`, retained when the same outgoing batch is retried over another connection. Receiver partial files and metadata are now scoped to a hash of that ID, so two transfers with the same filename and size cannot accidentally share or overwrite one another's resume data. A parked checkpoint is synced before its metadata is published, and the metadata is validated against the actual partial length and chunk sequence before offering resume. This is groundwork for cross-transport recovery, not yet automatic route switching; the coordinator still needs live adapter and checkpoint callbacks.
- Added receiver-side completed-file manifests keyed by the stable per-file offer ID. After BLAKE3 verification and durable commit, the receiver records the saved filename, expected size and verified digest. On a retry, it re-hashes the saved file before acknowledging `already_complete`, so a missing, resized or modified destination is transferred again instead of being incorrectly skipped. This prevents duplicate completed files when the same outgoing batch is retried; it still depends on reusing the same `OutgoingFile` identities and does not itself establish a new transport or coordinate the peer's route.
- Added an authenticated per-file completion receipt. The receiver sends it only after BLAKE3 verification, final-file commit and completion-manifest persistence; the sender waits for and validates the receipt before advancing to the next file or reporting that file as complete. If the connection drops after commit but before the receipt arrives, the next attempt is safe: the receiver's verified manifest answers `already_complete`. The sender API also exposes an `onFileCompleted` callback so the Android recovery coordinator can consume receiver-confirmed file completions. This is a file-boundary durable signal, not a mid-file checkpoint stream or end-to-end radio failover.
- Corrected cancellation semantics: Android transfer control now throws `CancellationException`, which the failover coordinator treats as terminal. A user pressing Cancel must never be interpreted as a failed radio route that triggers another transport attempt. A regression test exercises the actual `LanLink.TransferCancelledException` type.

- Added a deterministic logical batch ID derived from the ordered per-file stable IDs, names and declared sizes. Reusing the same selected `OutgoingFile` objects across a transport retry produces the same batch identity. Protocol v3 now sends that ID and expected file count in an authenticated `BatchStart` before any offers; the receiver acknowledges readiness and requires the terminal `BatchComplete` to repeat the same identity and count. This gives both peers a stable batch rendezvous token at the start of each connection, but Android still needs a receiver-side multi-transport rendezvous/listener and durable cross-connection session coordination before automatic cross-radio switching is end-to-end.

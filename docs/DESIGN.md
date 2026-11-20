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

Hello → PIN confirm → Offer (stable transfer identity) → Response (optional `resume_bytes`) → Chunk* → Complete / Cancel.

A transport reconnect creates a fresh authenticated channel and PIN handshake. It must not silently reset the file checkpoint. The receiver validates the partial file, expected size, sequence boundary and final BLAKE3 checksum before committing the completed file.

## Transport strategy

- Shared Wi-Fi / SoftAP use the existing TCP transport.
- Wi-Fi Direct group negotiation and TCP socket adapter are implemented in `WifiDirectLink`; the group owner accepts a TCP peer and the group client connects to the owner. The caller runs the same authenticated `LanLink` protocol over the returned `TransferConnection`.
- Bluetooth Classic RFCOMM uses a secure, paired-device socket adapter; it is a fallback / small-file option, not assumed to be available or faster.
- The policy layer orders only platform-confirmed available transports. An adapter must still establish a real connection before it is considered usable.
- If a route fails, the coordinator advances to the next available route while retaining the same selected files and transfer checkpoint.
- Never delete a partial file just because a socket or radio link failed.

## Implementation status

- Implemented: crypto, session, codec, Android LAN TCP, SoftAP + QR bootstrap, mDNS discovery, chunk integrity, partial-file resume.
- Added in Phase 3 foundation: transport ordering policy, immutable transfer recovery snapshots and deterministic failover sequence with unit tests.
- Added in this phase: a tested transport-failover coordinator that carries forward only validated durable checkpoints, prevents transfer identity/file-list changes, and stops rather than switching routes when the user cancels. Android transport connection adapters and live transfer callbacks are not yet wired into this coordinator; end-to-end automatic switching remains the next integration step.
- Implemented: native Wi-Fi Direct adapter and secure Bluetooth Classic RFCOMM socket adapter for already-paired devices. Bluetooth peer discovery and host/join controls are now wired into the Android UI; Android system pairing is used when a selected device is not already bonded. The transport-independent recovery coordinator is implemented; Android end-to-end automatic failover across radios remains pending.
- Implemented in this phase: native Wi-Fi Direct discovery, peer listing, group create/connect/remove, connection-info reporting and group-owner TCP socket setup; Wi-Fi Direct UI and LanLink orchestration; Bluetooth Classic secure RFCOMM host/connect adapter for bonded peers. Wiring the recovery coordinator to Android connection adapters and device-level interruption/resume tests remain next. Bluetooth discovery and transfer controls are implemented, but require two-phone validation across Android versions.

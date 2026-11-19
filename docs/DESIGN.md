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
- Multiple mediums: Wi‑Fi LAN, SoftAP, later Wi‑Fi Direct / BLE
- Streaming disk I/O with resume

## Non-goals (for now)

- Cloud relay
- Account systems
- Full cross-platform production UI (next milestone)

## Stack

| Layer | Tech |
|-------|------|
| Core | Rust workspace crate `nearby-share` |
| FFI | C ABI (JNA) + UniFFI path |
| Android | Kotlin app `com.crypticsystems.warpair` |
| Discovery | mDNS `_warpair._tcp`, SoftAP + QR |

## Protocol sketch

Hello → PIN confirm → Offer → Response (optional `resume_bytes`) → Chunk* → Complete / Cancel

## Roadmap status

Done: crypto, session, codec, Android demo path, SoftAP/QR bootstrap, resume, cancel, categorized library.  
Next: product UI polish, Wi‑Fi Direct group owner path, iOS.

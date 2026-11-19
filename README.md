# NearbyShare

**Offline, peer-to-peer nearby file sharing foundation** for Android (and later iOS).  
Rust core + native platform bindings. No cloud. Ever.

## Status

Phase 0 → early Phase 1 foundation.

- Real cryptography: X25519, ChaCha20-Poly1305, BLAKE3, HKDF
- Secure session handshake + AEAD channel + length framing
- Binary protocol codec (`Message` encode/decode)
- Transfer session state machine
- **UniFFI surface** for Kotlin / Swift (`FfiIdentity`, `FfiSession`, …)

See **[docs/DESIGN.md](docs/DESIGN.md)** for architecture and roadmap.  
See **[docs/FFI.md](docs/FFI.md)** for binding generation and the exported API.

## Goals

- Completely offline / local-only transfers
- Strong encryption and integrity by default
- Clean separation between platform radios and core logic
- Embeddable via UniFFI (Android first, then iOS)
- Progressive medium upgrade (BLE / LAN → Wi-Fi Direct / SoftAP)

## Project Structure

```
NearbyShare/
├── crates/nearby-share/
│   ├── src/
│   │   ├── api.rs         # UniFFI-exported surface
│   │   ├── crypto.rs      # X25519, AEAD, BLAKE3, PIN
│   │   ├── discovery.rs
│   │   ├── protocol.rs    # Messages + binary codec
│   │   ├── session.rs     # Handshake + secure channel
│   │   ├── transfer.rs
│   │   └── lib.rs
│   └── uniffi.toml
├── docs/
│   ├── DESIGN.md
│   └── FFI.md
├── Cargo.toml
└── rust-toolchain.toml
```

## Quick Start

```bash
git clone https://github.com/Cryptic-Systems/NearbyShare.git
cd NearbyShare
cargo test
cargo build -p nearby-share --release
```

Generate Kotlin bindings (see [docs/FFI.md](docs/FFI.md)):

```bash
cargo install uniffi-bindgen --version 0.28.3
uniffi-bindgen generate \
  --library target/release/libnearby_share.so \
  --language kotlin \
  --out-dir bindings/kotlin
```

## License

MIT

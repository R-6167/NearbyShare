# NearbyShare

**Offline, peer-to-peer nearby file sharing foundation** for Android (and later iOS).  
Rust core + native platform bindings. No cloud. Ever.

## Status

Early foundation (Phase 0).

- Workspace and crate scaffolding
- Core modules: `crypto`, `discovery`, `protocol`, `transfer`
- Design document and feature roadmap
- Placeholder cryptography (ready to swap for real X25519 + AEAD + BLAKE3)
- UniFFI configuration prepared

See **[docs/DESIGN.md](docs/DESIGN.md)** for the full architecture, security model, and phased roadmap.

## Goals

- Completely offline / local-only transfers
- Strong encryption and integrity by default
- Clean separation between platform radios and core logic
- Embeddable via UniFFI (Android first, then iOS)
- Progressive medium upgrade (BLE / LAN → Wi-Fi Direct / SoftAP)

## Project Structure

```
NearbyShare/
├── crates/
│   └── nearby-share/          # Platform-agnostic Rust core
│       ├── src/
│       │   ├── crypto.rs      # Identity, key exchange, PIN, hashing
│       │   ├── discovery.rs   # Peer + DiscoveryService trait
│       │   ├── protocol.rs    # Messages + mediums
│       │   ├── transfer.rs    # Session state machine
│       │   └── lib.rs
│       └── uniffi.toml
├── docs/
│   └── DESIGN.md              # Architecture & roadmap
├── Cargo.toml                 # Workspace
└── rust-toolchain.toml        # Android targets pre-declared
```

## Quick Start (developers)

```bash
git clone https://github.com/Cryptic-Systems/NearbyShare.git
cd NearbyShare
cargo check
cargo test
```

## License

MIT

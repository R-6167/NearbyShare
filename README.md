# NearbyShare

**Offline, peer-to-peer nearby file sharing foundation** for Android (and later iOS).  
Rust core + native platform bindings. No cloud. Ever.

## Status

Phase 0 → early Phase 1 foundation.

- Real cryptography: X25519, ChaCha20-Poly1305, BLAKE3, HKDF
- Secure session handshake + AEAD channel + length framing
- Binary protocol codec (`Message` encode/decode)
- Transfer session state machine
- UniFFI surface for Kotlin / Swift
- **Android Gradle project** (`android/`) with demo app + library module

See **[docs/DESIGN.md](docs/DESIGN.md)** for architecture and roadmap.  
See **[docs/FFI.md](docs/FFI.md)** and **[android/README.md](android/README.md)** for bindings and Gradle.

## Goals

- Completely offline / local-only transfers
- Strong encryption and integrity by default
- Clean separation between platform radios and core logic
- Embeddable via UniFFI (Android first, then iOS)
- Progressive medium upgrade (BLE / LAN → Wi-Fi Direct / SoftAP)

## Project Structure

```
NearbyShare/
├── crates/nearby-share/     # Rust core + UniFFI API
├── android/                 # Gradle: :nearbyshare library + :app demo
├── scripts/build-android-native.sh
├── docs/
├── Cargo.toml
└── rust-toolchain.toml
```

## Quick Start

```bash
git clone https://github.com/Cryptic-Systems/NearbyShare.git
cd NearbyShare
cargo test
```

### Android

```bash
cargo install cargo-ndk
cargo install uniffi-bindgen --version 0.28.3
./scripts/build-android-native.sh
# Open android/ in Android Studio → Run 'app'
```

## License

MIT

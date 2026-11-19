# NearbyShare

**Offline, peer-to-peer nearby file sharing foundation** for Android (and later iOS).  
Rust core + native platform bindings. No cloud. Ever.

## Status

Phase 0 → early Phase 1 foundation.

- Real cryptography: X25519, ChaCha20-Poly1305, BLAKE3, HKDF
- Secure session + binary protocol codec
- UniFFI surface for Kotlin
- **Android Gradle + CLI APK workflow** (no Android Studio required)

Docs: [DESIGN](docs/DESIGN.md) · [FFI](docs/FFI.md) · [Android](android/README.md)

## Quick start (Rust)

```bash
git clone https://github.com/Cryptic-Systems/NearbyShare.git
cd NearbyShare
cargo test
```

## Build a debug APK (no Android Studio)

```bash
# JDK 17 required
chmod +x scripts/*.sh

./scripts/setup-android-sdk.sh   # once – downloads SDK/NDK to ~/Android/Sdk
./scripts/build-apk.sh           # native + UniFFI + assembleDebug

# APK path:
#   android/app/build/outputs/apk/debug/app-debug.apk

# Phone (USB debugging on):
./scripts/install-apk.sh
# or: INSTALL=1 ./scripts/build-apk.sh
```

Shortcuts: `make sdk` · `make apk` · `make install`

## Project layout

```text
crates/nearby-share/     Rust core + UniFFI
android/                 :nearbyshare library + :app demo APK
scripts/                 SDK setup, native build, APK, adb install
```

## License

MIT

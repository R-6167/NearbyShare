# NearbyShare

**Offline, peer-to-peer nearby file sharing foundation** for Android (and later iOS).  
Rust core + native platform bindings. No cloud. Ever.

## Status

- Crypto, session, protocol codec, UniFFI surface
- Android demo APK with **on-device smoke test** (identity + handshake + seal/open)
- CLI + **GitHub Actions** APK build (no Android Studio required)

Docs: [DESIGN](docs/DESIGN.md) · [FFI](docs/FFI.md) · [Android](android/README.md)

## Test on your phone (GitHub only)

1. Wait for the **Android APK** workflow on `main` to go green  
2. Actions → run → Artifacts → **NearbyShare-demo-debug**  
3. Install `app-debug.apk` on the phone  
4. Open **NearbyShare Demo** → **Run core smoke test**  
5. Expect a report starting with `OK`

## Local Rust checks

```bash
cargo test
```

## Local APK (optional)

```bash
chmod +x scripts/*.sh
./scripts/setup-android-sdk.sh
./scripts/build-apk.sh
./scripts/install-apk.sh
```

## License

MIT

# WarPair

**Offline, peer-to-peer nearby file sharing** for Android (iOS later).  
Rust core + native bindings. No cloud.

> Repository folder may still show historical paths (`nearby-share`, `android/nearbyshare`); the **product name and Android app id** are **WarPair** / `com.crypticsystems.warpair`.

## Status

- Crypto, session, protocol codec, C ABI / UniFFI surface
- Android APK: smoke test, LAN transfer, SoftAP + QR bootstrap, resume, PIN confirm
- CLI + **GitHub Actions** APK (no Android Studio required)

Docs: [DESIGN](docs/DESIGN.md) · [WARPAIR](docs/WARPAIR.md) · [Android](android/README.md)

## Test on your phone (GitHub)

1. Wait for **Android APK** on `main` to go green  
2. Actions → Artifacts → **`WarPair-debug`**  
3. Install `WarPair-debug.apk` (or `app-debug.apk` fallback)  
4. Open **WarPair** → **Run core smoke test**  
5. Expect a report starting with `OK`

Uninstall any old **NearbyShare Demo** package first (`com.crypticsystems.nearbyshare.demo`).

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

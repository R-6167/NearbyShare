# UniFFI / Native Bindings

The `nearby-share` crate exposes a stable FFI surface via [UniFFI](https://mozilla.github.io/uniffi-rs/) (proc-macro API).

Android Gradle wiring lives under **`android/`** — see [android/README.md](../android/README.md).

## Feature flag

| Feature | Default | Effect |
|---------|---------|--------|
| `ffi`   | on      | Enables `uniffi` + `api` module |

```bash
cargo build -p nearby-share
# or without FFI:
cargo build -p nearby-share --no-default-features
```

## Exported surface

| Type / function | Role |
|-----------------|------|
| `libraryVersion()` / `libraryHello()` | Smoke checks |
| `FfiIdentity` | Generate / inspect device identity |
| `FfiSession` | Handshake, seal/open, PIN, mediums |
| `FfiMedium` | Transport medium enum |
| `FfiSessionPhase` | Session lifecycle for UI |
| `NearbyError` | Typed errors |
| `frameEncode` / `frameTryDecode` | Length-prefixed framing |

### Kotlin-shaped flow

```kotlin
val identity = FfiIdentity.generate("Pixel")
val session = FfiSession(identity, initiator = true, mediums = listOf(FfiMedium.WIFI_LAN))

val hello = session.beginHandshake()
// … send hello over TCP / BLE …
session.completeHandshake(peerHelloBytes)

val pin = session.pin()
val sealed = session.seal(payload)
val plain = session.open(sealed)
```

## One-shot Android build

```bash
# From repo root
./scripts/build-android-native.sh
# Then open android/ in Android Studio
```

The script runs `cargo-ndk` into `android/nearbyshare/src/main/jniLibs/` and
`uniffi-bindgen` into the library module’s Java source tree.

## Manual binding generation

```bash
cargo install uniffi-bindgen --version 0.28.3
cargo build -p nearby-share --release

uniffi-bindgen generate \
  --library target/release/libnearby_share.so \
  --language kotlin \
  --out-dir android/nearbyshare/src/main/java
```

Package name: `com.crypticsystems.nearbyshare` (see `crates/nearby-share/uniffi.toml`).

## Design notes

- **Transport stays native** – Rust owns crypto + session; Android owns sockets / BLE / Wi-Fi Direct.
- **Bytes in, bytes out** – Hello, seal/open, and frames are `ByteArray` so the app can plug any pipe.
- **No discovery in FFI yet** – scanning/advertising is platform-specific.

## Next FFI increments

1. `FfiTransfer` helpers
2. Callback interfaces for discovery events
3. Wire demo `MainActivity` to `libraryHello()` / `FfiSession` after first successful bindgen run

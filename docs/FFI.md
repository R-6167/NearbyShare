# UniFFI / Native Bindings

The `nearby-share` crate exposes a stable FFI surface via [UniFFI](https://mozilla.github.io/uniffi-rs/) (proc-macro API).

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

val pin = session.pin()          // show to user if desired
val sealed = session.seal(payload)
val plain = session.open(sealed)
```

## Generate bindings

Requires the `uniffi-bindgen` CLI matching the crate’s `uniffi` version (`0.28`).

```bash
# Install once
cargo install uniffi-bindgen --version 0.28.3

# Build the cdylib
cargo build -p nearby-share --release

# Kotlin
uniffi-bindgen generate \
  --library target/release/libnearby_share.so \
  --language kotlin \
  --out-dir bindings/kotlin

# Swift
uniffi-bindgen generate \
  --library target/release/libnearby_share.dylib \
  --language swift \
  --out-dir bindings/swift
```

On Android, point Gradle at the produced `.so` (per ABI) and the generated Kotlin sources under `com.crypticsystems.nearbyshare` (see `uniffi.toml`).

## Design notes

- **Transport stays native** – Rust owns crypto + session; Android owns sockets / BLE / Wi-Fi Direct.
- **Bytes in, bytes out** – Hello, seal/open, and frames are `ByteArray` so the app can plug any pipe.
- **No discovery in FFI yet** – scanning/advertising is platform-specific; inject peers from Kotlin/Swift later via callbacks if needed.

## Next FFI increments

1. Transfer offer / accept helpers (`FfiTransfer`)
2. Callback interfaces for discovery events
3. Gradle module + example Android app wiring

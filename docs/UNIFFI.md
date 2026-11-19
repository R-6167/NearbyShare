# UniFFI bindings

The Rust crate exposes a **stable UniFFI surface** in `crates/nearby-share/src/api.rs` (feature `ffi`, on by default).

## What is exported

| Area | Types / functions |
|------|-------------------|
| Identity | `FfiIdentity` |
| Session | `FfiSession` (handshake, seal, open, PIN) |
| Framing | `frame_encode`, `frame_try_decode` |
| Protocol | `msg_encode_offer`, `msg_encode_offer_multi`, `msg_encode_response`, `msg_encode_chunk`, `msg_encode_complete`, `msg_tag`, `msg_parse_offer`, … |
| Hashing | `FfiHasher`, `blake3_hash` |
| Health | `library_version`, `library_hello` |

Kotlin package (from `uniffi.toml`): `com.crypticsystems.nearbyshare`.

## Demo app vs UniFFI

The Android **demo APK** currently talks to the native library through the **C ABI + JNA** (`c_api`, `c_msg`, `c_hash`) so GitHub Actions can build without running `uniffi-bindgen` on the CI agent.

Both paths use the same crypto/session/protocol core. UniFFI is the preferred long-term API for product apps (and iOS).

## Generate Kotlin bindings locally

```bash
# From repo root
cargo build -p nearby-share --features ffi

# Install bindgen if needed
cargo install uniffi-bindgen --version 0.28.3

# Produce Kotlin sources (adjust out-dir as needed)
uniffi-bindgen generate \
  --library target/debug/libnearby_share.so \
  --language kotlin \
  --out-dir android/nearbyshare/src/main/java
```

Then load `libnearby_share` as today and call the generated `com.crypticsystems.nearbyshare` APIs instead of JNA.

## iOS

```bash
uniffi-bindgen generate \
  --library target/aarch64-apple-ios/release/libnearby_share.a \
  --language swift \
  --out-dir ios/Generated
```

See `uniffi.toml` for module naming.

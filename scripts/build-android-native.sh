#!/usr/bin/env bash
# Build libnearby_share.so for Android ABIs and generate UniFFI Kotlin bindings
# into android/nearbyshare.
#
# Prerequisites:
#   - rustup targets (see rust-toolchain.toml)
#   - cargo-ndk:  cargo install cargo-ndk
#   - uniffi-bindgen 0.28.x:  cargo install uniffi-bindgen --version 0.28.3
#   - Android NDK (ANDROID_NDK_HOME or sdk ndk)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/android/nearbyshare/src/main/jniLibs"
GEN_DIR="$ROOT/android/nearbyshare/src/main/java"
CRATE="$ROOT/crates/nearby-share"

mkdir -p "$JNI_DIR" "$GEN_DIR"

echo "==> Building release cdylib for Android (cargo-ndk)"
cd "$ROOT"

# Map: ABI directory name used by Android
# arm64-v8a, armeabi-v7a, x86_64, x86
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -t x86 \
  -o "$JNI_DIR" \
  build -p nearby-share --release

echo "==> Generating UniFFI Kotlin bindings"
# Prefer a host .so from the Android build if present; otherwise build host lib.
HOST_LIB=""
if [[ -f "$ROOT/target/release/libnearby_share.so" ]]; then
  HOST_LIB="$ROOT/target/release/libnearby_share.so"
elif [[ -f "$ROOT/target/release/libnearby_share.dylib" ]]; then
  HOST_LIB="$ROOT/target/release/libnearby_share.dylib"
elif [[ -f "$ROOT/target/release/nearby_share.dll" ]]; then
  HOST_LIB="$ROOT/target/release/nearby_share.dll"
else
  echo "    (host library missing – building for host)"
  cargo build -p nearby-share --release
  if [[ -f "$ROOT/target/release/libnearby_share.so" ]]; then
    HOST_LIB="$ROOT/target/release/libnearby_share.so"
  elif [[ -f "$ROOT/target/release/libnearby_share.dylib" ]]; then
    HOST_LIB="$ROOT/target/release/libnearby_share.dylib"
  else
    HOST_LIB="$ROOT/target/release/nearby_share.dll"
  fi
fi

uniffi-bindgen generate \
  --library "$HOST_LIB" \
  --language kotlin \
  --out-dir "$GEN_DIR"

echo "==> Done"
echo "    jniLibs: $JNI_DIR"
echo "    kotlin:  $GEN_DIR/com/crypticsystems/nearbyshare/"
echo ""
echo "Open android/ in Android Studio and run :app"

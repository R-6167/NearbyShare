#!/usr/bin/env bash
# Build debug APK from the command line (no Android Studio).
#
# Steps:
#   1) Ensure SDK + local.properties
#   2) Optionally build Rust native + UniFFI (SKIP_NATIVE=1 to skip)
#   3) ./gradlew :app:assembleDebug
#   4) Print APK path and optional adb install hint
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SKIP_NATIVE="${SKIP_NATIVE:-0}"
INSTALL="${INSTALL:-0}"

cd "$ROOT"

# --- SDK ---
if [[ ! -f "$ANDROID_DIR/local.properties" ]]; then
  echo "==> local.properties missing – running setup-android-sdk.sh"
  "$ROOT/scripts/setup-android-sdk.sh"
fi

# Load sdk.dir if ANDROID_HOME unset
if [[ -z "${ANDROID_HOME:-}" ]] && [[ -f "$ANDROID_DIR/local.properties" ]]; then
  # shellcheck disable=SC2002
  SDK_DIR="$(grep '^sdk.dir=' "$ANDROID_DIR/local.properties" | cut -d= -f2- | tr -d '\r')"
  export ANDROID_HOME="$SDK_DIR"
  export ANDROID_SDK_ROOT="$SDK_DIR"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]] && [[ -n "${ANDROID_HOME:-}" ]]; then
  # Pick newest installed NDK if present
  if [[ -d "$ANDROID_HOME/ndk" ]]; then
    NDK_CANDIDATE="$(ls -1 "$ANDROID_HOME/ndk" 2>/dev/null | sort -V | tail -1 || true)"
    if [[ -n "$NDK_CANDIDATE" ]]; then
      export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_CANDIDATE"
    fi
  fi
fi

# --- Gradle wrapper ---
if [[ ! -x "$ANDROID_DIR/gradlew" ]] || [[ ! -f "$ANDROID_DIR/gradle/wrapper/gradle-wrapper.jar" ]]; then
  "$ROOT/scripts/bootstrap-gradle-wrapper.sh"
fi

# --- Rust native + UniFFI ---
if [[ "$SKIP_NATIVE" != "1" ]]; then
  if command -v cargo >/dev/null 2>&1; then
    if ! command -v cargo-ndk >/dev/null 2>&1; then
      echo "==> Installing cargo-ndk"
      cargo install cargo-ndk
    fi
    if ! command -v uniffi-bindgen >/dev/null 2>&1; then
      echo "==> Installing uniffi-bindgen 0.28.3"
      cargo install uniffi-bindgen --version 0.28.3
    fi
    echo "==> Building native libraries + UniFFI Kotlin"
    "$ROOT/scripts/build-android-native.sh" || {
      echo "WARNING: native build failed – continuing with APK (lib may be missing at runtime)"
    }
  else
    echo "WARNING: cargo not found – skipping native build (SKIP_NATIVE effectively)"
  fi
else
  echo "==> SKIP_NATIVE=1 – not rebuilding Rust"
fi

# --- APK ---
echo "==> Gradle :app:assembleDebug"
cd "$ANDROID_DIR"
./gradlew :app:assembleDebug --stacktrace

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found at $APK"
  exit 1
fi

SIZE="$(du -h "$APK" | cut -f1)"
echo ""
echo "========================================"
echo " APK ready: $APK"
echo " Size:      $SIZE"
echo "========================================"
echo ""
echo "Install on a phone (USB debugging on):"
echo "  adb install -r $APK"
echo "Or:"
echo "  INSTALL=1 ./scripts/build-apk.sh"
echo ""

if [[ "$INSTALL" == "1" ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    if [[ -n "${ANDROID_HOME:-}" ]] && [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
      export PATH="$ANDROID_HOME/platform-tools:$PATH"
    fi
  fi
  if command -v adb >/dev/null 2>&1; then
    echo "==> adb install -r"
    adb install -r "$APK"
  else
    echo "adb not found – install platform-tools or add them to PATH"
    exit 1
  fi
fi

#!/usr/bin/env bash
# Install the latest debug APK on a connected device/emulator.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK"
  echo "Build first: ./scripts/build-apk.sh"
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  if [[ -n "${ANDROID_HOME:-}" ]] && [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
  elif [[ -f "$ROOT/android/local.properties" ]]; then
    SDK_DIR="$(grep '^sdk.dir=' "$ROOT/android/local.properties" | cut -d= -f2- | tr -d '\r')"
    export PATH="$SDK_DIR/platform-tools:$PATH"
  fi
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Run ./scripts/setup-android-sdk.sh first."
  exit 1
fi

echo "Devices:"
adb devices
echo "Installing $APK"
adb install -r "$APK"
echo "Done. Launch: NearbyShare Demo"

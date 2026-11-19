#!/usr/bin/env bash
# Create android/gradlew + gradle-wrapper.jar without Android Studio.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
GRADLE_VERSION="${GRADLE_VERSION:-8.11.1}"

cd "$ANDROID_DIR"

if [[ -x "./gradlew" ]] && [[ -f "./gradle/wrapper/gradle-wrapper.jar" ]]; then
  echo "==> Gradle wrapper already present"
  ./gradlew --version
  exit 0
fi

echo "==> Bootstrapping Gradle ${GRADLE_VERSION} wrapper"

# Prefer a system gradle if available
if command -v gradle >/dev/null 2>&1; then
  gradle wrapper --gradle-version "$GRADLE_VERSION"
  ./gradlew --version
  exit 0
fi

# Download official distribution and generate wrapper
TMP="$(mktemp -d)"
ZIP="$TMP/gradle-${GRADLE_VERSION}-bin.zip"
URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
echo "==> Downloading $URL"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL -o "$ZIP" "$URL"
else
  wget -q -O "$ZIP" "$URL"
fi
unzip -q "$ZIP" -d "$TMP"
"$TMP/gradle-${GRADLE_VERSION}/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION"
rm -rf "$TMP"

chmod +x ./gradlew
./gradlew --version
echo "==> Wrapper ready: android/gradlew"

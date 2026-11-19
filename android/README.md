# Android project (CLI-first)

You do **not** need Android Studio. Build and install a debug APK from the terminal.

```
android/
├── app/              # Demo APK host
├── nearbyshare/      # Library: jniLibs + UniFFI Kotlin + JNA
└── …
```

## One-time machine setup

### 1. JDK 17

```bash
# Ubuntu/Debian
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk unzip wget curl

# macOS (Homebrew)
brew install openjdk@17
```

### 2. Android SDK (no Studio)

From the **repo root**:

```bash
chmod +x scripts/*.sh
./scripts/setup-android-sdk.sh
```

This downloads Google command-line tools into `~/Android/Sdk` (or `$ANDROID_HOME`), installs:

- `platform-tools` (adb)
- `platforms;android-35`
- `build-tools;35.0.0`
- NDK

and writes `android/local.properties`.

### 3. Rust tooling (for native `.so`)

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk
cargo install uniffi-bindgen --version 0.28.3
```

## Build a debug APK

```bash
# From repo root – does SDK check, native build, Gradle assembleDebug
./scripts/build-apk.sh
```

Or stepwise:

```bash
make sdk        # once
make wrapper    # once (downloads Gradle, creates android/gradlew)
make native     # Rust .so + UniFFI Kotlin
make apk        # same as ./scripts/build-apk.sh
```

**Output:**

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Skip Rust (UI-only compile):

```bash
SKIP_NATIVE=1 ./scripts/build-apk.sh
```

## Install on your phone

1. On the phone: **Developer options → USB debugging** ON  
2. Connect USB (or use wireless adb)
3. Trust the computer when prompted

```bash
./scripts/install-apk.sh
# or in one step:
INSTALL=1 ./scripts/build-apk.sh
# or manually:
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Copy the APK to the phone any other way (Drive, Telegram, `scp`) and open it — allow “Install unknown apps” for that source.

## Modules

| Module | Role |
|--------|------|
| `:nearbyshare` | `libnearby_share.so` + UniFFI Kotlin + JNA |
| `:app` | Debug APK you install |

## After UniFFI generate

`scripts/build-android-native.sh` / `build-apk.sh` place generated Kotlin under:

```text
nearbyshare/src/main/java/com/crypticsystems/nearbyshare/
```

Then from the app:

```kotlin
import com.crypticsystems.nearbyshare.libraryHello
import com.crypticsystems.nearbyshare.FfiIdentity

libraryHello()
FfiIdentity.generate("Android")
```

## CI

GitHub Actions workflow `.github/workflows/android-apk.yml` builds the debug APK and uploads it as an artifact on pushes to `main`.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `SDK location not found` | `./scripts/setup-android-sdk.sh` |
| `gradlew: not found` | `./scripts/bootstrap-gradle-wrapper.sh` |
| `libnearby_share not found` | `./scripts/build-android-native.sh` (needs NDK + cargo-ndk) |
| `adb devices` empty | Enable USB debugging; try another cable/port |
| JDK errors | Use Java 17: `java -version` |

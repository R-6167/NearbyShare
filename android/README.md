# Android project

Gradle workspace that ships the Rust core via UniFFI.

```
android/
├── app/                 # Demo host app
├── nearbyshare/         # Android library (JNA + jniLibs + generated Kotlin)
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## Prerequisites

1. **Android Studio** (or SDK + NDK + JDK 17)
2. **Rust** with Android targets (see root `rust-toolchain.toml`)
3. **cargo-ndk**
   ```bash
   cargo install cargo-ndk
   ```
4. **uniffi-bindgen** (match crate: 0.28.x)
   ```bash
   cargo install uniffi-bindgen --version 0.28.3
   ```
5. `ANDROID_NDK_HOME` pointing at your NDK install

## Build native + bindings

From the **repo root**:

```bash
chmod +x scripts/build-android-native.sh
./scripts/build-android-native.sh
```

This will:

1. Cross-compile `libnearby_share.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
2. Copy them into `nearbyshare/src/main/jniLibs/<abi>/`
3. Generate UniFFI Kotlin under `nearbyshare/src/main/java/com/crypticsystems/nearbyshare/`

## Open & run

```bash
# Optional: generate Gradle wrapper jar if missing
cd android && gradle wrapper   # if you have a system Gradle

# Or open the android/ folder in Android Studio and Run 'app'
```

The demo app loads `libnearby_share` and shows a smoke-test button. After UniFFI generation you can call:

```kotlin
import com.crypticsystems.nearbyshare.libraryHello
import com.crypticsystems.nearbyshare.FfiIdentity

val hello = libraryHello()
val id = FfiIdentity.generate("Android")
```

## Modules

| Module | Type | Role |
|--------|------|------|
| `:nearbyshare` | Android library | Rust cdylib + UniFFI Kotlin + JNA |
| `:app` | Application | Minimal UI to verify the stack |

## Notes

- Radios (BLE, Wi-Fi Direct, mDNS) stay in the **app** layer; the library is crypto + session only for now.
- Generated Kotlin and `.so` files are local build products — see root `.gitignore`.

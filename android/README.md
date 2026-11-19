# Android project (CLI + GitHub Actions)

You do **not** need Android Studio. Prefer **GitHub Actions artifacts** if the machine is limited.

## Test on your phone via GitHub (recommended)

1. Push to `main` (or run **Actions → Android APK → Run workflow**).
2. Open the successful run → **Artifacts** → download **`NearbyShare-demo-debug`**.
3. Unzip → `app-debug.apk`.
4. Copy to the phone and install (or `adb install -r app-debug.apk`).
5. Open **NearbyShare Demo** → **Run core smoke test**.

You should see a report starting with `OK` plus version, fingerprint, PIN, and `seal_open=ok`.

## Local CLI build (optional)

```bash
chmod +x scripts/*.sh
./scripts/setup-android-sdk.sh   # once
./scripts/build-apk.sh           # native + APK
./scripts/install-apk.sh         # adb
```

APK path: `android/app/build/outputs/apk/debug/app-debug.apk`

## What the smoke test covers

The demo calls a stable **C ABI** inside `libnearby_share.so` (via JNA):

- identity keygen
- session handshake
- matching PIN
- encrypt / decrypt round-trip

No `uniffi-bindgen` step is required for this path.

## Modules

| Module | Role |
|--------|------|
| `:nearbyshare` | `.so` + `NearbyCore` JNA bridge |
| `:app` | Demo UI |

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| CI red on `cargo test` | Check Rust job logs first |
| CI red on `cargo ndk` | NDK path / targets |
| App: library not found | APK built without jniLibs – use Actions artifact from green run |
| `adb devices` empty | USB debugging + authorize PC |

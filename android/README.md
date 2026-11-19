# WarPair Android (CLI + GitHub Actions)

You do **not** need Android Studio. Prefer **GitHub Actions artifacts** if the machine is limited.

## Test on your phone via GitHub (recommended)

1. Push to `main` (or **Actions → Android APK → Run workflow**).
2. Open the successful run → **Artifacts** → download **`WarPair-debug`**.
3. Unzip → `WarPair-debug.apk` (or `app-debug.apk`).
4. Install on the phone (`adb install -r …`).
5. Open **WarPair** → **Run core smoke test**.

Package id: `com.crypticsystems.warpair`

## Local CLI build (optional)

```bash
chmod +x scripts/*.sh
./scripts/setup-android-sdk.sh   # once
./scripts/build-apk.sh           # native + APK
./scripts/install-apk.sh         # adb
```

## Modules

| Gradle project | Role |
|----------------|------|
| `:warpair-core` | `libnearby_share.so` + `NearbyCore` JNA (`com.crypticsystems.warpair.core`) |
| `:app` | WarPair UI (`com.crypticsystems.warpair`) |

Physical directory for the core module is still `android/nearbyshare/` (mapped in `settings.gradle.kts`).

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| CI red on `cargo test` | Check Rust job logs |
| App: library not found | Use a green Actions APK artifact |
| Old app still installed | Uninstall `com.crypticsystems.nearbyshare.demo` |

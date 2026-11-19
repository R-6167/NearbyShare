package com.crypticsystems.nearbyshare

/**
 * Ensures the Rust `nearby_share` cdylib is loaded.
 *
 * UniFFI-generated Kotlin (from `scripts/build-android-native.sh`) will also
 * load this library; this helper is useful for smoke checks before bindings exist.
 */
object NativeLoad {
    @Volatile
    private var loaded = false

    @JvmStatic
    fun ensure() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("nearby_share")
                    loaded = true
                }
            }
        }
    }
}

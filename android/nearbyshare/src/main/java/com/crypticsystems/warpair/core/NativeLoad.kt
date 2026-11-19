package com.crypticsystems.warpair.core

/** Ensures the Rust `nearby_share` cdylib is loaded. */
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

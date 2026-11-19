package com.crypticsystems.nearbyshare

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.charset.StandardCharsets

/**
 * Thin JNA bridge to the stable C ABI in `libnearby_share`.
 *
 * Works without `uniffi-bindgen` output so CI and the demo APK can always
 * exercise identity + session crypto on device.
 */
object NearbyCore {
    private interface NativeLib : Library {
        fun nearby_c_version(buf: ByteArray, len: Int): Int
        fun nearby_c_hello(buf: ByteArray, len: Int): Int
        fun nearby_c_smoke_test(buf: ByteArray, len: Int): Int
    }

    @Volatile
    private var lib: NativeLib? = null

    private fun lib(): NativeLib {
        val existing = lib
        if (existing != null) return existing
        synchronized(this) {
            lib?.let { return it }
            NativeLoad.ensure()
            val loaded = Native.load("nearby_share", NativeLib::class.java)
            lib = loaded
            return loaded
        }
    }

    private fun readString(call: (ByteArray, Int) -> Int): String {
        var size = 512
        repeat(4) {
            val buf = ByteArray(size)
            val n = call(buf, buf.size)
            when {
                n == -2 -> size *= 2
                n < 0 -> error("native error code $n")
                else -> return String(buf, 0, n, StandardCharsets.UTF_8)
            }
        }
        error("buffer too small for native string")
    }

    /** Library version (crate semver). */
    @JvmStatic
    fun version(): String = readString { buf, len -> lib().nearby_c_version(buf, len) }

    /** Health string, e.g. `NearbyShare core v0.1.0`. */
    @JvmStatic
    fun hello(): String = readString { buf, len -> lib().nearby_c_hello(buf, len) }

    /**
     * Full in-process smoke: two identities, handshake, PIN, seal/open.
     * Returns a multi-line report starting with `OK` on success.
     */
    @JvmStatic
    fun smokeTest(): String = readString { buf, len -> lib().nearby_c_smoke_test(buf, len) }
}

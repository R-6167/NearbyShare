package com.crypticsystems.nearbyshare

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.nio.charset.StandardCharsets

/**
 * JNA bridge to `libnearby_share` C ABI (smoke + session handles + framing).
 */
object NearbyCore {
    private interface NativeLib : Library {
        fun nearby_c_version(buf: ByteArray, len: Int): Int
        fun nearby_c_hello(buf: ByteArray, len: Int): Int
        fun nearby_c_smoke_test(buf: ByteArray, len: Int): Int

        fun nearby_c_session_create(name: ByteArray?, nameLen: Int, initiator: Int): Long
        fun nearby_c_session_free(handle: Long)
        fun nearby_c_session_begin_handshake(handle: Long, out: ByteArray, outLen: Int): Int
        fun nearby_c_session_complete_handshake(handle: Long, peer: ByteArray, peerLen: Int): Int
        fun nearby_c_session_pin(handle: Long, out: ByteArray, outLen: Int): Int
        fun nearby_c_session_peer_name(handle: Long, out: ByteArray, outLen: Int): Int
        fun nearby_c_session_seal(
            handle: Long,
            plain: ByteArray,
            plainLen: Int,
            out: ByteArray,
            outLen: Int,
        ): Int
        fun nearby_c_session_open(
            handle: Long,
            cipher: ByteArray,
            cipherLen: Int,
            out: ByteArray,
            outLen: Int,
        ): Int

        fun nearby_c_frame_encode(
            payload: ByteArray,
            payloadLen: Int,
            out: ByteArray,
            outLen: Int,
        ): Int

        fun nearby_c_frame_try_decode(
            buf: ByteArray,
            bufLen: Int,
            out: ByteArray,
            outLen: Int,
            consumedOut: IntByReference,
        ): Int
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
        repeat(5) {
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

    private fun readBytes(call: (ByteArray, Int) -> Int): ByteArray {
        var size = 1024
        repeat(6) {
            val buf = ByteArray(size)
            val n = call(buf, buf.size)
            when {
                n == -2 -> size *= 2
                n < 0 -> error("native error code $n")
                else -> return buf.copyOf(n)
            }
        }
        error("buffer too small for native bytes")
    }

    @JvmStatic
    fun version(): String = readString { buf, len -> lib().nearby_c_version(buf, len) }

    @JvmStatic
    fun hello(): String = readString { buf, len -> lib().nearby_c_hello(buf, len) }

    @JvmStatic
    fun smokeTest(): String = readString { buf, len -> lib().nearby_c_smoke_test(buf, len) }

    /** Opaque native session (must [close]). */
    class Session internal constructor(val handle: Long) : AutoCloseable {
        fun beginHandshake(): ByteArray =
            readBytes { out, len -> lib().nearby_c_session_begin_handshake(handle, out, len) }

        fun completeHandshake(peerHello: ByteArray) {
            val rc = lib().nearby_c_session_complete_handshake(handle, peerHello, peerHello.size)
            if (rc != 0) error("completeHandshake failed: $rc")
        }

        fun pin(): String =
            readString { out, len -> lib().nearby_c_session_pin(handle, out, len) }

        fun peerName(): String =
            readString { out, len -> lib().nearby_c_session_peer_name(handle, out, len) }

        fun seal(plain: ByteArray): ByteArray =
            readBytes { out, len -> lib().nearby_c_session_seal(handle, plain, plain.size, out, len) }

        fun open(cipher: ByteArray): ByteArray =
            readBytes { out, len -> lib().nearby_c_session_open(handle, cipher, cipher.size, out, len) }

        override fun close() {
            lib().nearby_c_session_free(handle)
        }
    }

    @JvmStatic
    fun createSession(name: String, initiator: Boolean): Session {
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        val h = lib().nearby_c_session_create(bytes, bytes.size, if (initiator) 1 else 0)
        if (h <= 0) error("createSession failed: $h")
        return Session(h)
    }

    @JvmStatic
    fun frameEncode(payload: ByteArray): ByteArray =
        readBytes { out, len -> lib().nearby_c_frame_encode(payload, payload.size, out, len) }

    data class FrameDecode(val payload: ByteArray?, val consumed: Int)

    @JvmStatic
    fun frameTryDecode(buffer: ByteArray): FrameDecode {
        val consumed = IntByReference(0)
        var size = maxOf(256, buffer.size)
        repeat(4) {
            val out = ByteArray(size)
            val n = lib().nearby_c_frame_try_decode(buffer, buffer.size, out, out.size, consumed)
            when {
                n == 0 -> return FrameDecode(null, 0)
                n == -2 -> size *= 2
                n < 0 -> error("frameTryDecode failed: $n")
                else -> return FrameDecode(out.copyOf(n), consumed.value)
            }
        }
        error("frameTryDecode buffer too small")
    }
}

package com.crypticsystems.nearbyshare

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import java.nio.charset.StandardCharsets

/** JNA bridge to `libnearby_share` (session + framing + protocol + hasher). */
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
        fun nearby_c_session_seal(handle: Long, plain: ByteArray, plainLen: Int, out: ByteArray, outLen: Int): Int
        fun nearby_c_session_open(handle: Long, cipher: ByteArray, cipherLen: Int, out: ByteArray, outLen: Int): Int

        fun nearby_c_frame_encode(payload: ByteArray, payloadLen: Int, out: ByteArray, outLen: Int): Int

        fun nearby_c_msg_encode_offer(
            sid: ByteArray, sidLen: Int, name: ByteArray, nameLen: Int, size: Long, out: ByteArray, outLen: Int,
        ): Int

        fun nearby_c_msg_encode_response(sid: ByteArray, sidLen: Int, accepted: Int, out: ByteArray, outLen: Int): Int

        fun nearby_c_msg_encode_chunk(
            sid: ByteArray, sidLen: Int, fileId: Int, seq: Long, data: ByteArray, dataLen: Int, isLast: Int,
            out: ByteArray, outLen: Int,
        ): Int

        fun nearby_c_msg_encode_complete(sid: ByteArray, sidLen: Int, hash: ByteArray, out: ByteArray, outLen: Int): Int

        fun nearby_c_msg_tag(data: ByteArray, dataLen: Int): Int

        fun nearby_c_msg_parse_offer(
            data: ByteArray, dataLen: Int,
            sidOut: ByteArray, sidCap: Int, sidLenOut: IntByReference,
            nameOut: ByteArray, nameCap: Int, nameLenOut: IntByReference,
            sizeOut: LongByReference,
        ): Int

        fun nearby_c_msg_parse_response(data: ByteArray, dataLen: Int, acceptedOut: IntByReference): Int

        fun nearby_c_msg_parse_chunk(
            data: ByteArray, dataLen: Int, seqOut: LongByReference, isLastOut: IntByReference,
            dataOut: ByteArray, dataOutLen: Int,
        ): Int

        fun nearby_c_hash(data: ByteArray, dataLen: Int, out: ByteArray): Int

        fun nearby_c_hasher_new(): Long
        fun nearby_c_hasher_update(handle: Long, data: ByteArray, len: Int): Int
        fun nearby_c_hasher_finalize(handle: Long, out: ByteArray): Int
        fun nearby_c_hasher_free(handle: Long)
    }

    @Volatile private var lib: NativeLib? = null

    private fun lib(): NativeLib {
        lib?.let { return it }
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
                n < 0 -> error("native error $n")
                else -> return String(buf, 0, n, StandardCharsets.UTF_8)
            }
        }
        error("string buffer too small")
    }

    private fun readBytes(call: (ByteArray, Int) -> Int): ByteArray {
        var size = 2048
        repeat(8) {
            val buf = ByteArray(size)
            val n = call(buf, buf.size)
            when {
                n == -2 -> size *= 2
                n < 0 -> error("native error $n")
                else -> return buf.copyOf(n)
            }
        }
        error("bytes buffer too small")
    }

    fun version(): String = readString { b, l -> lib().nearby_c_version(b, l) }
    fun hello(): String = readString { b, l -> lib().nearby_c_hello(b, l) }
    fun smokeTest(): String = readString { b, l -> lib().nearby_c_smoke_test(b, l) }

    class Session internal constructor(val handle: Long) : AutoCloseable {
        fun beginHandshake(): ByteArray =
            readBytes { o, l -> lib().nearby_c_session_begin_handshake(handle, o, l) }

        fun completeHandshake(peerHello: ByteArray) {
            val rc = lib().nearby_c_session_complete_handshake(handle, peerHello, peerHello.size)
            if (rc != 0) error("completeHandshake $rc")
        }

        fun pin(): String = readString { o, l -> lib().nearby_c_session_pin(handle, o, l) }
        fun peerName(): String = readString { o, l -> lib().nearby_c_session_peer_name(handle, o, l) }

        fun seal(plain: ByteArray): ByteArray =
            readBytes { o, l -> lib().nearby_c_session_seal(handle, plain, plain.size, o, l) }

        fun open(cipher: ByteArray): ByteArray =
            readBytes { o, l -> lib().nearby_c_session_open(handle, cipher, cipher.size, o, l) }

        override fun close() {
            lib().nearby_c_session_free(handle)
        }
    }

    fun createSession(name: String, initiator: Boolean): Session {
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        val h = lib().nearby_c_session_create(bytes, bytes.size, if (initiator) 1 else 0)
        if (h <= 0) error("createSession $h")
        return Session(h)
    }

    fun frameEncode(payload: ByteArray): ByteArray =
        readBytes { o, l -> lib().nearby_c_frame_encode(payload, payload.size, o, l) }

    fun encodeOffer(sessionId: String, fileName: String, size: Long): ByteArray {
        val sid = sessionId.toByteArray(StandardCharsets.UTF_8)
        val name = fileName.toByteArray(StandardCharsets.UTF_8)
        return readBytes { o, l -> lib().nearby_c_msg_encode_offer(sid, sid.size, name, name.size, size, o, l) }
    }

    fun encodeResponse(sessionId: String, accepted: Boolean): ByteArray {
        val sid = sessionId.toByteArray(StandardCharsets.UTF_8)
        return readBytes { o, l -> lib().nearby_c_msg_encode_response(sid, sid.size, if (accepted) 1 else 0, o, l) }
    }

    fun encodeChunk(sessionId: String, fileId: Int, seq: Long, data: ByteArray, isLast: Boolean): ByteArray {
        val sid = sessionId.toByteArray(StandardCharsets.UTF_8)
        return readBytes { o, l ->
            lib().nearby_c_msg_encode_chunk(sid, sid.size, fileId, seq, data, data.size, if (isLast) 1 else 0, o, l)
        }
    }

    fun encodeComplete(sessionId: String, hash32: ByteArray): ByteArray {
        require(hash32.size == 32)
        val sid = sessionId.toByteArray(StandardCharsets.UTF_8)
        return readBytes { o, l -> lib().nearby_c_msg_encode_complete(sid, sid.size, hash32, o, l) }
    }

    fun msgTag(data: ByteArray): Int = lib().nearby_c_msg_tag(data, data.size)

    data class OfferInfo(val sessionId: String, val fileName: String, val size: Long)

    fun parseOffer(data: ByteArray): OfferInfo {
        val sid = ByteArray(256)
        val name = ByteArray(512)
        val sidLen = IntByReference()
        val nameLen = IntByReference()
        val size = LongByReference()
        val rc = lib().nearby_c_msg_parse_offer(
            data, data.size,
            sid, sid.size, sidLen,
            name, name.size, nameLen,
            size,
        )
        if (rc != 0) error("parseOffer $rc")
        return OfferInfo(
            String(sid, 0, sidLen.value, StandardCharsets.UTF_8),
            String(name, 0, nameLen.value, StandardCharsets.UTF_8),
            size.value,
        )
    }

    fun parseResponseAccepted(data: ByteArray): Boolean {
        val acc = IntByReference()
        val rc = lib().nearby_c_msg_parse_response(data, data.size, acc)
        if (rc != 0) error("parseResponse $rc")
        return acc.value != 0
    }

    data class ChunkInfo(val seq: Long, val isLast: Boolean, val data: ByteArray)

    fun parseChunk(data: ByteArray): ChunkInfo {
        val seq = LongByReference()
        val last = IntByReference()
        val payload = readBytes { o, l -> lib().nearby_c_msg_parse_chunk(data, data.size, seq, last, o, l) }
        return ChunkInfo(seq.value, last.value != 0, payload)
    }

    fun hash(data: ByteArray): ByteArray {
        val out = ByteArray(32)
        val n = lib().nearby_c_hash(data, data.size, out)
        if (n != 32) error("hash failed $n")
        return out
    }

    /** Streaming BLAKE3 hasher (does not keep payload in JVM heap beyond each update). */
    class Hasher internal constructor(private var handle: Long) : AutoCloseable {
        fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            require(handle > 0)
            val slice = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
            val rc = lib().nearby_c_hasher_update(handle, slice, slice.size)
            if (rc != 0) error("hasher update $rc")
        }

        fun finalizeHash(): ByteArray {
            require(handle > 0)
            val out = ByteArray(32)
            val n = lib().nearby_c_hasher_finalize(handle, out)
            handle = -1
            if (n != 32) error("hasher finalize $n")
            return out
        }

        override fun close() {
            if (handle > 0) {
                lib().nearby_c_hasher_free(handle)
                handle = -1
            }
        }
    }

    fun newHasher(): Hasher {
        val h = lib().nearby_c_hasher_new()
        if (h <= 0) error("hasher_new $h")
        return Hasher(h)
    }
}

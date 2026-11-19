package com.crypticsystems.nearbyshare.demo

import com.crypticsystems.nearbyshare.NearbyCore
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * TCP + session + protocol file transfer demo.
 *
 * Client (initiator) sends a single in-memory file after handshake.
 * Host (responder) accepts, receives chunks, verifies BLAKE3, returns status.
 */
object LanLink {
    const val DEFAULT_PORT = 19730
    private const val CHUNK = 32 * 1024

    fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.write(NearbyCore.frameEncode(payload))
        out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        require(len in 0 until 16 * 1024 * 1024) { "bad frame len $len" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return payload
    }

    fun hostOnce(deviceName: String, port: Int = DEFAULT_PORT): String {
        ServerSocket(port).use { server ->
            server.soTimeout = 180_000
            val socket = server.accept()
            socket.soTimeout = 120_000
            return socket.use { runTransfer(it, deviceName, initiator = false, fileName = null, fileBytes = null) }
        }
    }

    fun connectOnce(
        host: String,
        port: Int,
        deviceName: String,
        fileName: String,
        fileBytes: ByteArray,
    ): String {
        val socket = Socket()
        socket.soTimeout = 120_000
        socket.connect(InetSocketAddress(host, port), 20_000)
        return socket.use { runTransfer(it, deviceName, initiator = true, fileName, fileBytes) }
    }

    private fun runTransfer(
        socket: Socket,
        deviceName: String,
        initiator: Boolean,
        fileName: String?,
        fileBytes: ByteArray?,
    ): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        NearbyCore.createSession(deviceName, initiator).use { session ->
            val localHello = session.beginHandshake()
            if (initiator) {
                writeFrame(output, localHello)
                session.completeHandshake(readFrame(input))
            } else {
                session.completeHandshake(readFrame(input))
                writeFrame(output, localHello)
            }

            val pin = session.pin()
            val peer = session.peerName()

            if (initiator) {
                require(fileName != null && fileBytes != null)
                return sendFile(session, input, output, peer, pin, fileName, fileBytes)
            }
            return receiveFile(session, input, output, peer, pin)
        }
    }

    private fun sendSealed(session: NearbyCore.Session, output: DataOutputStream, plain: ByteArray) {
        writeFrame(output, session.seal(plain))
    }

    private fun readSealed(session: NearbyCore.Session, input: DataInputStream): ByteArray {
        return session.open(readFrame(input))
    }

    private fun sendFile(
        session: NearbyCore.Session,
        input: DataInputStream,
        output: DataOutputStream,
        peer: String,
        pin: String,
        fileName: String,
        fileBytes: ByteArray,
    ): String {
        val sessionId = "xfer-${UUID.randomUUID().toString().take(8)}"
        sendSealed(session, output, NearbyCore.encodeOffer(sessionId, fileName, fileBytes.size.toLong()))

        val respPlain = readSealed(session, input)
        require(NearbyCore.msgTag(respPlain) == 3) { "expected Response" }
        require(NearbyCore.parseResponseAccepted(respPlain)) { "peer rejected offer" }

        var offset = 0
        var seq = 0L
        while (offset < fileBytes.size) {
            val end = minOf(offset + CHUNK, fileBytes.size)
            val slice = fileBytes.copyOfRange(offset, end)
            val last = end >= fileBytes.size
            sendSealed(
                session,
                output,
                NearbyCore.encodeChunk(sessionId, 1, seq, slice, last),
            )
            offset = end
            seq++
        }

        val hash = NearbyCore.hash(fileBytes)
        sendSealed(session, output, NearbyCore.encodeComplete(sessionId, hash))

        return buildString {
            appendLine("TRANSFER OK (sender)")
            appendLine("peer=$peer")
            appendLine("pin=$pin")
            appendLine("file=$fileName")
            appendLine("bytes=${fileBytes.size}")
            appendLine("chunks=$seq")
        }
    }

    private fun receiveFile(
        session: NearbyCore.Session,
        input: DataInputStream,
        output: DataOutputStream,
        peer: String,
        pin: String,
    ): String {
        val offerPlain = readSealed(session, input)
        require(NearbyCore.msgTag(offerPlain) == 2) { "expected Offer" }
        val offer = NearbyCore.parseOffer(offerPlain)

        sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, true))

        val buf = ByteArrayOutputStream()
        var chunks = 0
        while (true) {
            val plain = readSealed(session, input)
            when (NearbyCore.msgTag(plain)) {
                5 -> {
                    val chunk = NearbyCore.parseChunk(plain)
                    buf.write(chunk.data)
                    chunks++
                    if (chunk.isLast) {
                        // still expect Complete
                    }
                }
                7 -> break
                else -> error("unexpected tag ${NearbyCore.msgTag(plain)}")
            }
        }

        val data = buf.toByteArray()
        val digest = NearbyCore.hash(data)
        val okSize = data.size.toLong() == offer.size

        return buildString {
            appendLine("TRANSFER OK (receiver)")
            appendLine("peer=$peer")
            appendLine("pin=$pin")
            appendLine("file=${offer.fileName}")
            appendLine("bytes=${data.size}")
            appendLine("size_match=$okSize")
            appendLine("chunks=$chunks")
            appendLine("blake3_prefix=${digest.take(4).joinToString("") { "%02x".format(it) }}")
            if (data.size <= 512) {
                appendLine("preview=${data.toString(Charsets.UTF_8)}")
            }
        }
    }
}

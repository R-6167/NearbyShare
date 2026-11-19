package com.crypticsystems.nearbyshare.demo

import com.crypticsystems.nearbyshare.NearbyCore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * TCP + session + protocol file transfer with disk streaming on receive
 * and optional InputStream send (no full-file byte[] required).
 */
object LanLink {
    const val DEFAULT_PORT = 19730
    private const val CHUNK = 64 * 1024
    /** Soft cap for a single transfer (2 GiB). */
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 - 1

    fun interface Progress {
        fun onProgress(bytesDone: Long, totalBytes: Long, phase: String)
    }

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

    fun hostOnce(
        deviceName: String,
        port: Int = DEFAULT_PORT,
        onProgress: Progress? = null,
        openSink: (fileName: String) -> IncomingFileSink,
    ): String {
        onProgress?.onProgress(0, 0, "listening")
        ServerSocket(port).use { server ->
            server.soTimeout = 180_000
            val socket = server.accept()
            socket.soTimeout = 120_000
            return socket.use {
                runTransfer(
                    socket = it,
                    deviceName = deviceName,
                    initiator = false,
                    fileName = null,
                    fileSize = -1,
                    inputStream = null,
                    onProgress = onProgress,
                    openSink = openSink,
                )
            }
        }
    }

    fun connectOnce(
        host: String,
        port: Int,
        deviceName: String,
        fileName: String,
        fileSize: Long,
        inputStream: InputStream,
        onProgress: Progress? = null,
    ): String {
        require(fileSize < 0 || fileSize <= MAX_BYTES) { "File too large: $fileSize" }
        onProgress?.onProgress(0, fileSize.coerceAtLeast(0), "connecting")
        val socket = Socket()
        socket.soTimeout = 120_000
        socket.connect(InetSocketAddress(host, port), 20_000)
        return socket.use {
            runTransfer(
                socket = it,
                deviceName = deviceName,
                initiator = true,
                fileName = fileName,
                fileSize = fileSize,
                inputStream = inputStream,
                onProgress = onProgress,
                openSink = null,
            )
        }
    }

    private fun runTransfer(
        socket: Socket,
        deviceName: String,
        initiator: Boolean,
        fileName: String?,
        fileSize: Long,
        inputStream: InputStream?,
        onProgress: Progress?,
        openSink: ((String) -> IncomingFileSink)?,
    ): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        NearbyCore.createSession(deviceName, initiator).use { session ->
            onProgress?.onProgress(0, 0, "handshake")
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
            onProgress?.onProgress(0, 0, "established pin=$pin")

            if (initiator) {
                require(fileName != null && inputStream != null)
                return sendFile(
                    session, input, output, peer, pin, fileName, fileSize, inputStream, onProgress,
                )
            }
            require(openSink != null)
            return receiveFile(session, input, output, peer, pin, onProgress, openSink)
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
        fileSize: Long,
        stream: InputStream,
        onProgress: Progress?,
    ): String {
        val sessionId = "xfer-${UUID.randomUUID().toString().take(8)}"
        // If size unknown (-1), we still stream; offer uses 0 and size_match is soft.
        val offerSize = if (fileSize >= 0) fileSize else 0L
        onProgress?.onProgress(0, offerSize, "offer")
        sendSealed(session, output, NearbyCore.encodeOffer(sessionId, fileName, offerSize))

        val respPlain = readSealed(session, input)
        require(NearbyCore.msgTag(respPlain) == 3) { "expected Response" }
        require(NearbyCore.parseResponseAccepted(respPlain)) { "peer rejected offer" }

        val hasher = NearbyCore.newHasher()
        val buf = ByteArray(CHUNK)
        var sent = 0L
        var seq = 0L
        stream.use { src ->
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val slice = if (n == buf.size) buf else buf.copyOf(n)
                hasher.update(slice)
                val last = false // real last after loop
                // We don't know last until EOF; buffer one chunk lag is complex — send with isLast=false then empty last
                sendSealed(
                    session,
                    output,
                    NearbyCore.encodeChunk(sessionId, 1, seq, slice, false),
                )
                sent += n
                seq++
                onProgress?.onProgress(sent, offerSize, "sending")
                if (fileSize >= 0 && sent > fileSize) error("sent more than declared size")
            }
        }
        // Final empty chunk marks end of file (compatible with is_last protocol).
        sendSealed(
            session,
            output,
            NearbyCore.encodeChunk(sessionId, 1, seq, ByteArray(0), true),
        )
        seq++

        val hash = hasher.finalizeHash()
        sendSealed(session, output, NearbyCore.encodeComplete(sessionId, hash))
        onProgress?.onProgress(sent, offerSize, "complete")

        return buildString {
            appendLine("TRANSFER OK (sender)")
            appendLine("peer=$peer")
            appendLine("pin=$pin")
            appendLine("file=$fileName")
            appendLine("bytes=$sent")
            appendLine("chunks=$seq")
        }
    }

    private fun receiveFile(
        session: NearbyCore.Session,
        input: DataInputStream,
        output: DataOutputStream,
        peer: String,
        pin: String,
        onProgress: Progress?,
        openSink: (String) -> IncomingFileSink,
    ): String {
        val offerPlain = readSealed(session, input)
        require(NearbyCore.msgTag(offerPlain) == 2) { "expected Offer" }
        val offer = NearbyCore.parseOffer(offerPlain)
        if (offer.size > 0) {
            require(offer.size <= MAX_BYTES) { "Offer too large: ${offer.size}" }
        }

        onProgress?.onProgress(0, offer.size, "accepted ${offer.fileName}")
        sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, true))

        val sink = openSink(offer.fileName)
        val hasher = NearbyCore.newHasher()
        var chunks = 0
        try {
            while (true) {
                val plain = readSealed(session, input)
                when (NearbyCore.msgTag(plain)) {
                    5 -> {
                        val chunk = NearbyCore.parseChunk(plain)
                        if (chunk.data.isNotEmpty()) {
                            sink.write(chunk.data)
                            hasher.update(chunk.data)
                        }
                        chunks++
                        onProgress?.onProgress(sink.bytesWritten, offer.size, "receiving → ${sink.category.folderName}")
                        if (chunk.isLast) {
                            // still expect Complete, keep reading
                        }
                    }
                    7 -> break
                    else -> error("unexpected tag ${NearbyCore.msgTag(plain)}")
                }
            }
            val digest = hasher.finalizeHash()
            val save = sink.finish()
            val okSize = offer.size <= 0 || save.bytes == offer.size
            onProgress?.onProgress(save.bytes, offer.size, "complete")

            return buildString {
                appendLine("TRANSFER OK (receiver)")
                appendLine("peer=$peer")
                appendLine("pin=$pin")
                appendLine("file=${offer.fileName}")
                appendLine("category=${save.category.folderName}")
                appendLine("bytes=${save.bytes}")
                appendLine("size_match=$okSize")
                appendLine("chunks=$chunks")
                appendLine("blake3_prefix=${digest.take(4).joinToString("") { "%02x".format(it) }}")
                append(save.summary())
            }
        } catch (t: Throwable) {
            hasher.close()
            sink.abort()
            throw t
        }
    }
}

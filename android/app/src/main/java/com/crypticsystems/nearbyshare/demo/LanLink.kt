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
 * TCP + session + protocol file transfer with disk streaming on receive.
 */
object LanLink {
    const val DEFAULT_PORT = 19730
    private const val CHUNK = 64 * 1024
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 - 1

    fun interface Progress {
        fun onProgress(bytesDone: Long, totalBytes: Long, phase: String)
    }

    data class OfferRequest(
        val peerName: String,
        val pin: String,
        val sessionId: String,
        val fileName: String,
        val size: Long,
        val category: FileCategory,
    )

    /** Return true to accept the offer and receive, false to reject. */
    fun interface OfferDecision {
        fun decide(offer: OfferRequest): Boolean
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
        onOffer: OfferDecision,
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
                    onOffer = onOffer,
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
                onOffer = null,
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
        onOffer: OfferDecision?,
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
            require(onOffer != null && openSink != null)
            return receiveFile(session, input, output, peer, pin, onProgress, onOffer, openSink)
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
        val offerSize = if (fileSize >= 0) fileSize else 0L
        onProgress?.onProgress(0, offerSize, "offer — waiting for peer")
        sendSealed(session, output, NearbyCore.encodeOffer(sessionId, fileName, offerSize))

        val respPlain = readSealed(session, input)
        require(NearbyCore.msgTag(respPlain) == 3) { "expected Response" }
        if (!NearbyCore.parseResponseAccepted(respPlain)) {
            return buildString {
                appendLine("TRANSFER REJECTED by peer")
                appendLine("peer=$peer")
                appendLine("pin=$pin")
                appendLine("file=$fileName")
            }
        }

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
        onOffer: OfferDecision,
        openSink: (String) -> IncomingFileSink,
    ): String {
        val offerPlain = readSealed(session, input)
        require(NearbyCore.msgTag(offerPlain) == 2) { "expected Offer" }
        val offer = NearbyCore.parseOffer(offerPlain)
        if (offer.size > 0) {
            require(offer.size <= MAX_BYTES) { "Offer too large: ${offer.size}" }
        }

        val category = ReceivedFiles.categoryFor(offer.fileName)
        onProgress?.onProgress(0, offer.size, "offer from $peer — waiting for decision")

        val accepted = onOffer.decide(
            OfferRequest(
                peerName = peer,
                pin = pin,
                sessionId = offer.sessionId,
                fileName = offer.fileName,
                size = offer.size,
                category = category,
            ),
        )

        sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, accepted))

        if (!accepted) {
            onProgress?.onProgress(0, 0, "rejected")
            return buildString {
                appendLine("TRANSFER REJECTED (host)")
                appendLine("peer=$peer")
                appendLine("pin=$pin")
                appendLine("file=${offer.fileName}")
                appendLine("bytes_offered=${offer.size}")
            }
        }

        onProgress?.onProgress(0, offer.size, "accepted ${offer.fileName}")
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
                        onProgress?.onProgress(
                            sink.bytesWritten,
                            offer.size,
                            "receiving → ${sink.category.folderName}",
                        )
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

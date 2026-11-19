package com.crypticsystems.nearbyshare.demo

import com.crypticsystems.nearbyshare.NearbyCore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * TCP + session + protocol file transfer.
 *
 * After one handshake, sender may push one or more files (Offer → Response → Chunks → Complete).
 * Receiver loops until the peer closes the connection.
 */
object LanLink {
    const val DEFAULT_PORT = 19730
    private const val CHUNK = 64 * 1024
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 - 1

    fun interface Progress {
        fun onProgress(bytesDone: Long, totalBytes: Long, phase: String)
    }

    data class OutgoingFile(
        val fileName: String,
        val size: Long,
        val openStream: () -> InputStream,
    )

    data class OfferRequest(
        val peerName: String,
        val pin: String,
        val sessionId: String,
        val fileName: String,
        val size: Long,
        val category: FileCategory,
        val index: Int,
        val totalInBatch: Int,
    )

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
            socket.soTimeout = 180_000
            return socket.use {
                runHost(it, deviceName, onProgress, onOffer, openSink)
            }
        }
    }

    fun connectOnce(
        host: String,
        port: Int,
        deviceName: String,
        files: List<OutgoingFile>,
        onProgress: Progress? = null,
    ): String {
        require(files.isNotEmpty()) { "no files to send" }
        for (f in files) {
            require(f.size < 0 || f.size <= MAX_BYTES) { "File too large: ${f.fileName}" }
        }
        onProgress?.onProgress(0, 0, "connecting (${files.size} file(s))")
        val socket = Socket()
        socket.soTimeout = 180_000
        socket.connect(InetSocketAddress(host, port), 20_000)
        return socket.use {
            runClient(it, deviceName, files, onProgress)
        }
    }

    private fun runHost(
        socket: Socket,
        deviceName: String,
        onProgress: Progress?,
        onOffer: OfferDecision,
        openSink: (String) -> IncomingFileSink,
    ): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        NearbyCore.createSession(deviceName, false).use { session ->
            onProgress?.onProgress(0, 0, "handshake")
            session.completeHandshake(readFrame(input))
            writeFrame(output, session.beginHandshake())

            val pin = session.pin()
            val peer = session.peerName()
            onProgress?.onProgress(0, 0, "established pin=$pin")

            val reports = mutableListOf<String>()
            var index = 0
            while (true) {
                val offerPlain = try {
                    readSealed(session, input)
                } catch (_: EOFException) {
                    break
                } catch (_: SocketTimeoutException) {
                    break
                } catch (t: Throwable) {
                    if (reports.isNotEmpty()) break
                    throw t
                }
                if (NearbyCore.msgTag(offerPlain) != 2) {
                    error("expected Offer, got tag ${NearbyCore.msgTag(offerPlain)}")
                }
                index++
                reports += receiveOne(
                    session, input, output, peer, pin, offerPlain, index, onProgress, onOffer, openSink,
                )
            }

            if (reports.isEmpty()) {
                return "No files received from $peer (pin=$pin)"
            }
            return buildString {
                appendLine("BATCH OK (receiver) — ${reports.size} transfer(s)")
                appendLine("peer=$peer")
                appendLine("pin=$pin")
                reports.forEachIndexed { i, r ->
                    appendLine("--- file ${i + 1} ---")
                    appendLine(r.trimEnd())
                }
            }
        }
    }

    private fun runClient(
        socket: Socket,
        deviceName: String,
        files: List<OutgoingFile>,
        onProgress: Progress?,
    ): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        NearbyCore.createSession(deviceName, true).use { session ->
            onProgress?.onProgress(0, 0, "handshake")
            writeFrame(output, session.beginHandshake())
            session.completeHandshake(readFrame(input))

            val pin = session.pin()
            val peer = session.peerName()
            onProgress?.onProgress(0, 0, "established pin=$pin")

            val reports = mutableListOf<String>()
            files.forEachIndexed { i, file ->
                reports += sendOne(
                    session, input, output, peer, pin, file, i + 1, files.size, onProgress,
                )
            }

            return buildString {
                appendLine("BATCH OK (sender) — ${files.size} file(s)")
                appendLine("peer=$peer")
                appendLine("pin=$pin")
                reports.forEachIndexed { i, r ->
                    appendLine("--- file ${i + 1} ---")
                    appendLine(r.trimEnd())
                }
            }
        }
    }

    private fun sendSealed(session: NearbyCore.Session, output: DataOutputStream, plain: ByteArray) {
        writeFrame(output, session.seal(plain))
    }

    private fun readSealed(session: NearbyCore.Session, input: DataInputStream): ByteArray {
        return session.open(readFrame(input))
    }

    private fun sendOne(
        session: NearbyCore.Session,
        input: DataInputStream,
        output: DataOutputStream,
        peer: String,
        pin: String,
        file: OutgoingFile,
        index: Int,
        total: Int,
        onProgress: Progress?,
    ): String {
        val sessionId = "xfer-${UUID.randomUUID().toString().take(8)}"
        val offerSize = if (file.size >= 0) file.size else 0L
        onProgress?.onProgress(0, offerSize, "[$index/$total] offer ${file.fileName}")
        sendSealed(session, output, NearbyCore.encodeOffer(sessionId, file.fileName, offerSize))

        val respPlain = readSealed(session, input)
        require(NearbyCore.msgTag(respPlain) == 3) { "expected Response" }
        if (!NearbyCore.parseResponseAccepted(respPlain)) {
            return "REJECTED ${file.fileName}"
        }

        val hasher = NearbyCore.newHasher()
        val buf = ByteArray(CHUNK)
        var sent = 0L
        var seq = 0L
        file.openStream().use { src ->
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
                onProgress?.onProgress(sent, offerSize, "[$index/$total] sending ${file.fileName}")
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
        onProgress?.onProgress(sent, offerSize, "[$index/$total] complete ${file.fileName}")

        return "OK ${file.fileName} bytes=$sent chunks=$seq peer=$peer pin=$pin"
    }

    private fun receiveOne(
        session: NearbyCore.Session,
        input: DataInputStream,
        output: DataOutputStream,
        peer: String,
        pin: String,
        offerPlain: ByteArray,
        index: Int,
        onProgress: Progress?,
        onOffer: OfferDecision,
        openSink: (String) -> IncomingFileSink,
    ): String {
        val offer = NearbyCore.parseOffer(offerPlain)
        if (offer.size > 0) {
            require(offer.size <= MAX_BYTES) { "Offer too large: ${offer.size}" }
        }
        val category = ReceivedFiles.categoryFor(offer.fileName)
        onProgress?.onProgress(0, offer.size, "[$index] offer ${offer.fileName}")

        val accepted = onOffer.decide(
            OfferRequest(
                peerName = peer,
                pin = pin,
                sessionId = offer.sessionId,
                fileName = offer.fileName,
                size = offer.size,
                category = category,
                index = index,
                totalInBatch = 0,
            ),
        )
        sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, accepted))

        if (!accepted) {
            return "REJECTED ${offer.fileName}"
        }

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
                            "[$index] receiving ${offer.fileName} → ${sink.category.folderName}",
                        )
                    }
                    7 -> break
                    else -> error("unexpected tag ${NearbyCore.msgTag(plain)}")
                }
            }
            val digest = hasher.finalizeHash()
            val save = sink.finish()
            return buildString {
                appendLine("OK ${save.fileName}")
                appendLine("category=${save.category.folderName}")
                appendLine("bytes=${save.bytes}")
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

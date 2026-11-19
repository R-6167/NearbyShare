package com.crypticsystems.warpair

import com.crypticsystems.warpair.core.NearbyCore
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
import java.util.concurrent.atomic.AtomicBoolean

object LanLink {
    const val DEFAULT_PORT = 19730
    private const val CHUNK = 64 * 1024
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 - 1

    class TransferControl {
        private val cancelled = AtomicBoolean(false)
        fun cancel() { cancelled.set(true) }
        fun isCancelled(): Boolean = cancelled.get()
        fun check() { if (cancelled.get()) throw TransferCancelledException() }
    }

    class TransferCancelledException : RuntimeException("transfer cancelled")

    fun interface Progress { fun onProgress(bytesDone: Long, totalBytes: Long, phase: String) }
    fun interface PinConfirm { fun confirm(peerName: String, pin: String): Boolean }

    data class OutgoingFile(val fileName: String, val size: Long, val openStream: () -> InputStream)

    data class OfferRequest(
        val peerName: String, val pin: String, val sessionId: String, val fileName: String,
        val size: Long, val category: FileCategory, val index: Int, val partial: PartialInfo?,
    )

    enum class OfferAction { REJECT, ACCEPT_NEW, RESUME }
    data class OfferResult(val action: OfferAction, val resumeFrom: PartialInfo? = null)
    fun interface OfferDecision { fun decide(offer: OfferRequest): OfferResult }

    fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.write(NearbyCore.frameEncode(payload)); out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val header = ByteArray(4); input.readFully(header)
        val len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        require(len in 0 until 16 * 1024 * 1024) { "bad frame len $len" }
        val payload = ByteArray(len); input.readFully(payload); return payload
    }

    fun hostOnce(
        deviceName: String, port: Int = DEFAULT_PORT, control: TransferControl = TransferControl(),
        onProgress: Progress? = null, onPinConfirm: PinConfirm, onOffer: OfferDecision,
    ): String {
        onProgress?.onProgress(0, 0, "listening")
        ServerSocket(port).use { server ->
            server.soTimeout = 180_000
            val socket = server.accept(); socket.soTimeout = 180_000
            return socket.use { runHost(it, deviceName, control, onProgress, onPinConfirm, onOffer) }
        }
    }

    fun connectOnce(
        host: String, port: Int, deviceName: String, files: List<OutgoingFile>,
        control: TransferControl = TransferControl(), onProgress: Progress? = null, onPinConfirm: PinConfirm,
    ): String {
        require(files.isNotEmpty())
        Socket().use { socket ->
            socket.soTimeout = 180_000
            socket.connect(InetSocketAddress(host, port), 20_000)
            return runClient(socket, deviceName, files, control, onProgress, onPinConfirm)
        }
    }

    private fun runHost(
        socket: Socket, deviceName: String, control: TransferControl, onProgress: Progress?,
        onPinConfirm: PinConfirm, onOffer: OfferDecision,
    ): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        NearbyCore.createSession(deviceName, false).use { session ->
            session.completeHandshake(readFrame(input))
            writeFrame(output, session.beginHandshake())
            val pin = session.pin(); val peer = session.peerName()
            if (!onPinConfirm.confirm(peer, pin)) return "PIN not confirmed (host)"
            control.check()
            val reports = mutableListOf<String>(); var index = 0
            while (true) {
                control.check()
                val offerPlain = try { readSealed(session, input) } catch (_: EOFException) { break }
                catch (_: SocketTimeoutException) { break }
                catch (t: Throwable) { if (reports.isNotEmpty()) break else throw t }
                when (NearbyCore.msgTag(offerPlain)) {
                    8 -> return "Peer cancelled"
                    2 -> Unit
                    else -> error("expected Offer")
                }
                index++; reports += receiveOne(session, input, output, peer, pin, offerPlain, index, control, onProgress, onOffer)
            }
            if (reports.isEmpty()) return "No files from $peer"
            return "BATCH OK (receiver) — ${reports.size}\n" + reports.joinToString("\n---\n")
        }
    }

    private fun runClient(
        socket: Socket, deviceName: String, files: List<OutgoingFile>, control: TransferControl,
        onProgress: Progress?, onPinConfirm: PinConfirm,
    ): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        NearbyCore.createSession(deviceName, true).use { session ->
            writeFrame(output, session.beginHandshake())
            session.completeHandshake(readFrame(input))
            val pin = session.pin(); val peer = session.peerName()
            if (!onPinConfirm.confirm(peer, pin)) return "PIN not confirmed (sender)"
            val reports = files.mapIndexed { i, f ->
                control.check()
                sendOne(session, input, output, peer, pin, f, i + 1, files.size, control, onProgress)
            }
            return "BATCH OK (sender) — ${files.size}\n" + reports.joinToString("\n---\n")
        }
    }

    private fun sendSealed(session: NearbyCore.Session, output: DataOutputStream, plain: ByteArray) =
        writeFrame(output, session.seal(plain))

    private fun readSealed(session: NearbyCore.Session, input: DataInputStream) =
        session.open(readFrame(input))

    private fun sendOne(
        session: NearbyCore.Session, input: DataInputStream, output: DataOutputStream,
        peer: String, pin: String, file: OutgoingFile, index: Int, total: Int,
        control: TransferControl, onProgress: Progress?,
    ): String {
        val sessionId = "xfer-${UUID.randomUUID().toString().take(8)}"
        val offerSize = if (file.size >= 0) file.size else 0L
        sendSealed(session, output, NearbyCore.encodeOffer(sessionId, file.fileName, offerSize))
        val respPlain = readSealed(session, input)
        if (NearbyCore.msgTag(respPlain) == 8) return "CANCELLED by peer"
        val response = NearbyCore.parseResponse(respPlain)
        if (!response.accepted) return "REJECTED ${file.fileName}"
        val skip = response.reason?.removePrefix("resume_bytes:")?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val hasher = NearbyCore.newHasher()
        val buf = ByteArray(CHUNK)
        var sent = skip
        var seq = if (skip > 0) (skip + CHUNK - 1) / CHUNK else 0L
        try {
            file.openStream().use { src ->
                var rem = skip
                while (rem > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                    if (n < 0) error("resume EOF")
                    hasher.update(buf, 0, n); rem -= n
                }
                while (true) {
                    control.check()
                    val n = src.read(buf); if (n < 0) break; if (n == 0) continue
                    val slice = if (n == buf.size) buf else buf.copyOf(n)
                    hasher.update(slice)
                    sendSealed(session, output, NearbyCore.encodeChunk(sessionId, 1, seq, slice, false))
                    sent += n; seq++
                    onProgress?.onProgress(sent, offerSize, "[$index/$total] sending")
                }
            }
            sendSealed(session, output, NearbyCore.encodeChunk(sessionId, 1, seq, ByteArray(0), true))
            sendSealed(session, output, NearbyCore.encodeComplete(sessionId, hasher.finalizeHash()))
            return "OK ${file.fileName} bytes=$sent skip=$skip"
        } catch (_: TransferCancelledException) {
            try { sendSealed(session, output, NearbyCore.encodeCancel(sessionId, "user_cancel")) } catch (_: Throwable) {}
            return "CANCELLED ${file.fileName}"
        }
    }

    private fun receiveOne(
        session: NearbyCore.Session, input: DataInputStream, output: DataOutputStream,
        peer: String, pin: String, offerPlain: ByteArray, index: Int,
        control: TransferControl, onProgress: Progress?, onOffer: OfferDecision,
    ): String {
        val offer = NearbyCore.parseOffer(offerPlain)
        val category = ReceivedFiles.categoryFor(offer.fileName)
        val partial = ReceivedFiles.findPartial(offer.fileName, offer.size)
        val result = onOffer.decide(
            OfferRequest(peer, pin, offer.sessionId, offer.fileName, offer.size, category, index, partial),
        )
        if (control.isCancelled() || result.action == OfferAction.REJECT) {
            sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, false))
            return "REJECTED ${offer.fileName}"
        }
        val resume = if (result.action == OfferAction.RESUME) result.resumeFrom else null
        val reason = if (resume != null && resume.bytesReceived > 0) "resume_bytes:${resume.bytesReceived}" else null
        sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, true, reason))
        val sink = ReceivedFiles.openIncoming(offer.fileName, resume)
        val hasher = NearbyCore.newHasher()
        val resumed = resume != null && resume.bytesReceived > 0
        try {
            while (true) {
                control.check()
                val plain = readSealed(session, input)
                when (NearbyCore.msgTag(plain)) {
                    5 -> {
                        val chunk = NearbyCore.parseChunk(plain)
                        if (chunk.data.isNotEmpty()) {
                            sink.write(chunk.data)
                            if (!resumed) hasher.update(chunk.data)
                        }
                        sink.noteSeq(chunk.seq)
                        onProgress?.onProgress(sink.bytesWritten, offer.size, "[$index] receiving")
                    }
                    7 -> break
                    8 -> { sink.abort(true); return "CANCELLED by peer" }
                    else -> error("bad tag")
                }
            }
            if (!resumed) hasher.finalizeHash() else hasher.close()
            val save = sink.finish()
            return "OK ${save.fileName} bytes=${save.bytes} resumed=$resumed"
        } catch (_: TransferCancelledException) {
            hasher.close()
            try { sendSealed(session, output, NearbyCore.encodeCancel(offer.sessionId, "user_cancel")) } catch (_: Throwable) {}
            sink.abort(true)
            return "CANCELLED ${offer.fileName}"
        } catch (t: Throwable) {
            hasher.close(); sink.abort(true); throw t
        }
    }
}

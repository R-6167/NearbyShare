package com.crypticsystems.warpair

import com.crypticsystems.warpair.core.NearbyCore
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.IOException
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

    class TransferCancelledException : java.util.concurrent.CancellationException("transfer cancelled")

    fun interface Progress { fun onProgress(bytesDone: Long, totalBytes: Long, phase: String) }
    fun interface PinConfirm { fun confirm(peerName: String, pin: String): Boolean }

    data class OutgoingFile(
        val fileName: String,
        val size: Long,
        /** Stable for this selected file across transport retries within one logical send. */
        val sessionId: String = "xfer-${UUID.randomUUID()}",
        val openStream: () -> InputStream,
    )

    data class OfferRequest(
        val peerName: String, val pin: String, val sessionId: String, val fileName: String,
        val size: Long, val category: FileCategory, val index: Int, val partial: PartialInfo?,
    )

    enum class OfferAction { REJECT, ACCEPT_NEW, RESUME }
    data class OfferResult(val action: OfferAction, val resumeFrom: PartialInfo? = null)
    fun interface OfferDecision { fun decide(offer: OfferRequest): OfferResult }

    private fun measureStream(stream: InputStream): Long {
        var total = 0L
        val buffer = ByteArray(CHUNK)
        stream.use { source ->
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                require(total <= MAX_BYTES - n) { "file exceeds maximum supported size" }
                total += n
            }
        }
        return total
    }

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
        onProgress: Progress? = null, onListening: ((Int) -> Unit)? = null,
        onPinConfirm: PinConfirm, onOffer: OfferDecision,
    ): String {
        onProgress?.onProgress(0, 0, "binding listener")
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }.use { server ->
            server.soTimeout = 180_000
            onListening?.invoke(server.localPort)
            onProgress?.onProgress(0, 0, "listening on ${server.localPort} — waiting for peer")
            val socket = server.accept(); socket.soTimeout = 180_000
            return socket.use { accepted ->
                hostConnected(SocketTransferConnection(accepted), deviceName, control, onProgress, onPinConfirm, onOffer)
            }
        }
    }

    /** Run the receiver protocol over any established bidirectional transport. */
    fun hostConnected(
        connection: TransferConnection, deviceName: String,
        control: TransferControl = TransferControl(), onProgress: Progress? = null,
        onPinConfirm: PinConfirm, onOffer: OfferDecision,
    ): String = connection.use {
        runHost(it.input, it.output, deviceName, control, onProgress, onPinConfirm, onOffer)
    }

    fun connectOnce(
        host: String, port: Int, deviceName: String, files: List<OutgoingFile>,
        control: TransferControl = TransferControl(), onProgress: Progress? = null, onPinConfirm: PinConfirm,
    ): String {
        require(files.isNotEmpty())
        require(host.isNotBlank()) { "peer address is empty" }
        require(port in 1..65535) { "peer port must be between 1 and 65535" }
        Socket().use { socket ->
            socket.soTimeout = 180_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            try {
                socket.connect(InetSocketAddress(host, port), 12_000)
            } catch (e: ConnectException) {
                throw IOException(
                    "Connection refused by $host:$port. The other phone must tap HOST / RECEIVE and keep that screen open before connecting. " +
                        "Confirm both phones are on the same Wi-Fi/SoftAP and use the host phone's current Wi-Fi IP. " +
                        "This phone's IPv4 addresses: ${localIpv4Addresses()}",
                    e,
                )
            } catch (e: SocketTimeoutException) {
                throw IOException(
                    "Connection to $host:$port timed out. Check that both phones are on the same Wi-Fi/SoftAP, " +
                        "that the host is listening, and that hotspot client isolation is disabled. " +
                        "This phone's IPv4 addresses: ${localIpv4Addresses()}",
                    e,
                )
            }
            return sendConnected(SocketTransferConnection(socket), deviceName, files, control, onProgress, onPinConfirm)
        }
    }

    private fun localIpv4Addresses(): String = try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { ni ->
                ni.inetAddresses.asSequence()
                    .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    .map { "${ni.name}=${it.hostAddress}" }
            }?.toList()?.joinToString(", ")?.ifBlank { "(none found)" } ?: "(none found)"
    } catch (_: Throwable) { "(unavailable)" }

    private fun runHost(
        inputStream: InputStream, outputStream: OutputStream, deviceName: String,
        control: TransferControl, onProgress: Progress?,
        onPinConfirm: PinConfirm, onOffer: OfferDecision,
    ): String {
        val input = DataInputStream(inputStream)
        val output = DataOutputStream(outputStream)
        NearbyCore.createSession(deviceName, false).use { session ->
            session.completeHandshake(readFrame(input))
            writeFrame(output, session.beginHandshake())
            val pin = session.pin(); val peer = session.peerName()
            if (!onPinConfirm.confirm(peer, pin)) return "PIN not confirmed (host)"
            control.check()
            val reports = mutableListOf<String>(); var index = 0
            while (true) {
                control.check()
                val message = try {
                    readSealed(session, input)
                } catch (e: EOFException) {
                    throw IOException("Peer disconnected before confirming batch completion", e)
                } catch (e: SocketTimeoutException) {
                    throw IOException("Timed out before peer confirmed batch completion", e)
                }
                when (NearbyCore.msgTag(message)) {
                    8 -> return "Peer cancelled"
                    2 -> {
                        index++
                        reports += receiveOne(session, input, output, peer, pin, message, index, control, onProgress, onOffer)
                    }
                    9 -> {
                        val complete = NearbyCore.parseBatchComplete(message)
                        require(complete.batchId.isNotBlank()) { "batch id is missing" }
                        require(complete.fileCount == reports.size && reports.isNotEmpty()) {
                            "batch completion count mismatch: expected ${reports.size}, received ${complete.fileCount}"
                        }
                        val allAccepted = reports.all { it.startsWith("OK ") }
                        val status = if (allAccepted) "BATCH OK" else "BATCH FINISHED WITH ISSUES"
                        return "$status (receiver) — ${reports.size}\n" + reports.joinToString("\n---\n")
                    }
                    else -> error("expected Offer or BatchComplete")
                }
            }
            error("receiver loop ended without batch completion")
        }
    }

    /** Run the sender protocol over any established bidirectional transport. */
    fun sendConnected(
        connection: TransferConnection, deviceName: String, files: List<OutgoingFile>,
        control: TransferControl = TransferControl(), onProgress: Progress? = null,
        onPinConfirm: PinConfirm,
        onFileCompleted: ((OutgoingFile) -> Unit)? = null,
    ): String {
        require(files.isNotEmpty())
        return connection.use {
            runClient(it.input, it.output, deviceName, files, control, onProgress, onPinConfirm, onFileCompleted)
        }
    }

    private fun runClient(
        inputStream: InputStream, outputStream: OutputStream, deviceName: String,
        files: List<OutgoingFile>, control: TransferControl,
        onProgress: Progress?, onPinConfirm: PinConfirm,
        onFileCompleted: ((OutgoingFile) -> Unit)?,
    ): String {
        val input = DataInputStream(inputStream)
        val output = DataOutputStream(outputStream)
        NearbyCore.createSession(deviceName, true).use { session ->
            writeFrame(output, session.beginHandshake())
            session.completeHandshake(readFrame(input))
            val pin = session.pin(); val peer = session.peerName()
            if (!onPinConfirm.confirm(peer, pin)) return "PIN not confirmed (sender)"
            val reports = files.mapIndexed { i, f ->
                control.check()
                sendOne(session, input, output, peer, pin, f, i + 1, files.size, control, onProgress, onFileCompleted)
            }
            control.check()
            val batchId = "batch-${UUID.randomUUID()}"
            sendSealed(session, output, NearbyCore.encodeBatchComplete(batchId, reports.size))
            val allAccepted = reports.all { it.startsWith("OK ") }
            val status = if (allAccepted) "BATCH OK" else "BATCH FINISHED WITH ISSUES"
            return "$status (sender) — ${files.size}\n" + reports.joinToString("\n---\n")
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
        onFileCompleted: ((OutgoingFile) -> Unit)?,
    ): String {
        val sessionId = file.sessionId.also { require(it.isNotBlank()) { "file session id is required" } }
        require(file.size == -1L || file.size in 0..MAX_BYTES) {
            "file size is invalid or exceeds $MAX_BYTES bytes"
        }
        val offerSize = if (file.size >= 0) file.size else measureStream(file.openStream())
        sendSealed(session, output, NearbyCore.encodeOffer(sessionId, file.fileName, offerSize))
        val respPlain = readSealed(session, input)
        if (NearbyCore.msgTag(respPlain) == 8) return "CANCELLED by peer"
        val response = NearbyCore.parseResponse(respPlain)
        if (!response.accepted) return "REJECTED ${file.fileName}"
        if (response.reason == "already_complete") {
            onFileCompleted?.invoke(file)
            return "OK ${file.fileName} already_complete"
        }
        val skip = response.reason?.removePrefix("resume_bytes:")?.toLongOrNull() ?: 0L
        require(skip in 0..offerSize && (skip == offerSize || skip % CHUNK == 0L)) {
            "receiver requested an invalid resume offset"
        }
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
            require(sent == offerSize) { "source size changed: expected $offerSize bytes, read $sent" }
            sendSealed(session, output, NearbyCore.encodeChunk(sessionId, 1, seq, ByteArray(0), true))
            sendSealed(session, output, NearbyCore.encodeComplete(sessionId, hasher.finalizeHash()))
            val receipt = NearbyCore.parseResponse(readSealed(session, input))
            TransferFileReceipt.requireAccepted(sessionId, receipt.accepted, receipt.reason)
            onFileCompleted?.invoke(file)
            return "OK ${file.fileName} bytes=$sent skip=$skip receiver_confirmed"
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
        if (offer.size !in 0..MAX_BYTES) {
            sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, false, "invalid_file_size"))
            return "REJECTED ${offer.fileName}: invalid file size"
        }
        val completed = ReceivedFiles.findCompleted(offer.fileName, offer.size, offer.sessionId)
        if (completed != null) {
            sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, true, "already_complete"))
            return "OK ${completed.fileName} bytes=${completed.bytes} already_complete"
        }
        val category = ReceivedFiles.categoryFor(offer.fileName)
        val partial = ReceivedFiles.findPartial(offer.fileName, offer.size, offer.sessionId)
        val result = onOffer.decide(
            OfferRequest(peer, pin, offer.sessionId, offer.fileName, offer.size, category, index, partial),
        )
        if (control.isCancelled() || result.action == OfferAction.REJECT) {
            sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, false))
            return "REJECTED ${offer.fileName}"
        }
        val requestedResume = if (result.action == OfferAction.RESUME) result.resumeFrom else null
        val resume = requestedResume?.takeIf {
            it.partialFile.isFile && it.bytesReceived == it.partialFile.length() &&
                it.bytesReceived in 1 until offer.size && it.bytesReceived % CHUNK == 0L &&
                it.nextSeq == it.bytesReceived / CHUNK
        }
        if (requestedResume != null && resume == null) {
            sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, false, "invalid_resume_state"))
            return "REJECTED ${offer.fileName}: invalid resume state"
        }
        val reason = if (resume != null) "resume_bytes:${resume.bytesReceived}" else null
        sendSealed(session, output, NearbyCore.encodeResponse(offer.sessionId, true, reason))
        val sink = ReceivedFiles.openIncoming(offer.fileName, resume, offer.sessionId, offer.size)
        val hasher = NearbyCore.newHasher()
        var discardPartialOnFailure = false
        val validator = TransferIntegrityValidator(
            sessionId = offer.sessionId,
            expectedSize = offer.size,
            initialBytes = resume?.bytesReceived ?: 0L,
            initialSequence = resume?.nextSeq ?: 0L,
        )
        try {
            if (resume != null) {
                resume.partialFile.inputStream().buffered().use { existing ->
                    val prefix = ByteArray(CHUNK)
                    while (true) {
                        val n = existing.read(prefix)
                        if (n < 0) break
                        if (n > 0) hasher.update(prefix, 0, n)
                    }
                }
            }

            while (true) {
                control.check()
                val plain = readSealed(session, input)
                when (NearbyCore.msgTag(plain)) {
                    5 -> {
                        val chunk = NearbyCore.parseChunk(plain)
                        validator.acceptChunk(
                            chunk.sessionId, chunk.fileId, chunk.seq, chunk.data.size, chunk.isLast,
                        )
                        if (chunk.data.isNotEmpty()) {
                            sink.write(chunk.data)
                            hasher.update(chunk.data)
                        }
                        sink.noteSeq(chunk.seq)
                        onProgress?.onProgress(validator.bytesReceived, offer.size, "[$index] receiving")
                    }
                    7 -> {
                        val complete = NearbyCore.parseComplete(plain)
                        val actualHash = hasher.finalizeHash()
                        discardPartialOnFailure = true
                        validator.verifyComplete(
                            complete.sessionId, complete.fileId, complete.checksum, actualHash,
                        )
                        val save = sink.finish()
                        ReceivedFiles.recordCompleted(
                            offer.sessionId, offer.fileName, offer.size, save, actualHash,
                        )
                        sendSealed(
                            session, output,
                            NearbyCore.encodeResponse(offer.sessionId, true, TransferFileReceipt.reason(offer.sessionId)),
                        )
                        return "OK ${save.fileName} bytes=${save.bytes} resumed=${resume != null}"
                    }
                    8 -> { sink.abort(true); return "CANCELLED by peer" }
                    else -> error("unexpected message while receiving file")
                }
            }
        } catch (_: TransferCancelledException) {
            hasher.close()
            try { sendSealed(session, output, NearbyCore.encodeCancel(offer.sessionId, "user_cancel")) } catch (_: Throwable) {}
            sink.abort(true)
            return "CANCELLED ${offer.fileName}"
        } catch (t: Throwable) {
            hasher.close()
            sink.abort(!discardPartialOnFailure)
            throw t
        }
    }
}

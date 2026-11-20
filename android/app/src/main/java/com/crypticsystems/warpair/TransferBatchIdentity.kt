package com.crypticsystems.warpair

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic identity for one ordered selection of files.
 *
 * The same OutgoingFile objects produce the same batch ID when retried over
 * another transport. File contents are still authenticated independently by
 * the per-file BLAKE3 protocol.
 */
object TransferBatchIdentity {
    fun from(files: List<LanLink.OutgoingFile>): String {
        require(files.isNotEmpty()) { "at least one file is required" }
        val ids = files.map { it.sessionId }
        require(ids.all { it.isNotBlank() }) { "file session ids are required" }
        require(ids.distinct().size == ids.size) { "file session ids must be unique" }

        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            updateField(digest, file.sessionId)
            updateField(digest, file.fileName)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.size).array())
        }
        return "batch-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
}

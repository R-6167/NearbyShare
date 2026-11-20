package com.crypticsystems.warpair

import java.io.IOException
import java.security.MessageDigest

/**
 * Correlates a sender's completed-file message with the receiver's durable
 * commit. The authenticated LanLink session protects this receipt in transit.
 */
object TransferFileReceipt {
    private const val PREFIX = "file_complete:"

    fun reason(fileSessionId: String): String {
        require(fileSessionId.isNotBlank()) { "file session id is required" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fileSessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PREFIX + digest
    }

    @Throws(IOException::class)
    fun requireAccepted(fileSessionId: String, accepted: Boolean, reasonText: String?) {
        if (!accepted || reasonText != reason(fileSessionId)) {
            throw IOException("Receiver did not confirm durable completion for file $fileSessionId")
        }
    }
}

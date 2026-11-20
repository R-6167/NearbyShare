package com.crypticsystems.warpair

import java.io.IOException

/**
 * Correlates a sender's completed-file message with the receiver's durable
 * commit. The authenticated LanLink session protects this receipt in transit.
 */
object TransferFileReceipt {
    private const val PREFIX = "file_complete:"

    fun reason(fileSessionId: String): String {
        require(fileSessionId.isNotBlank()) { "file session id is required" }
        return PREFIX + fileSessionId
    }

    @Throws(IOException::class)
    fun requireAccepted(fileSessionId: String, accepted: Boolean, reason: String?) {
        if (!accepted || reason != reason(fileSessionId)) {
            throw IOException("Receiver did not confirm durable completion for file $fileSessionId")
        }
    }
}

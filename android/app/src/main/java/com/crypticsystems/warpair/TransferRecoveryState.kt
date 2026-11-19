package com.crypticsystems.warpair

/**
 * Immutable state for one logical send operation. Transport adapters receive
 * this state but must never replace it when a socket/radio connection fails.
 */
data class TransferFileDescriptor(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
) {
    init {
        require(id.isNotBlank()) { "file id is required" }
        require(fileName.isNotBlank()) { "file name is required" }
        require(sizeBytes >= 0) { "file size cannot be negative" }
    }
}

/**
 * Receiver-confirmed durable checkpoint. Partial checkpoints are only valid
 * at full chunk boundaries, matching the current 64 KiB wire chunk size.
 */
data class TransferFileCheckpoint(
    val fileId: String,
    val sizeBytes: Long,
    val bytesDurable: Long,
    val nextChunkSequence: Long,
    val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE,
) {
    init {
        require(fileId.isNotBlank())
        require(sizeBytes >= 0)
        require(bytesDurable in 0..sizeBytes)
        require(nextChunkSequence >= 0)
        require(chunkSizeBytes > 0)
        require(bytesDurable == sizeBytes || bytesDurable % chunkSizeBytes == 0L) {
            "partial checkpoint must end on a complete chunk boundary"
        }
        require(
            bytesDurable == sizeBytes ||
                nextChunkSequence == bytesDurable / chunkSizeBytes,
        ) { "checkpoint sequence does not match durable byte offset" }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }
}

/**
 * A transport-independent snapshot. Updating a checkpoint returns a new
 * snapshot and leaves the previous snapshot intact for safe retry decisions.
 */
data class TransferRecoveryState(
    val transferId: String,
    val files: List<TransferFileDescriptor>,
    val checkpoints: Map<String, TransferFileCheckpoint> = emptyMap(),
    val completedFileIds: Set<String> = emptySet(),
) {
    init {
        require(transferId.isNotBlank()) { "transfer id is required" }
        require(files.isNotEmpty()) { "at least one file is required" }
        require(files.map { it.id }.distinct().size == files.size) { "file ids must be unique" }
        val knownIds = files.map { it.id }.toSet()
        require(checkpoints.keys.all { it in knownIds }) { "checkpoint references an unknown file" }
        require(completedFileIds.all { it in knownIds }) { "completion references an unknown file" }
        checkpoints.forEach { (id, checkpoint) ->
            require(checkpoint.fileId == id) { "checkpoint file id mismatch" }
            val file = files.first { it.id == id }
            require(checkpoint.sizeBytes == file.sizeBytes) { "checkpoint size mismatch" }
        }
    }

    fun recordCheckpoint(checkpoint: TransferFileCheckpoint): TransferRecoveryState {
        require(files.any { it.id == checkpoint.fileId }) { "checkpoint references an unknown file" }
        require(files.first { it.id == checkpoint.fileId }.sizeBytes == checkpoint.sizeBytes) {
            "checkpoint size mismatch"
        }
        require(checkpoint.fileId !in completedFileIds) { "completed files cannot be resumed" }
        return copy(checkpoints = checkpoints + (checkpoint.fileId to checkpoint))
    }

    fun markCompleted(fileId: String): TransferRecoveryState {
        require(files.any { it.id == fileId }) { "completion references an unknown file" }
        return copy(
            checkpoints = checkpoints - fileId,
            completedFileIds = completedFileIds + fileId,
        )
    }

    fun remainingFiles(): List<TransferFileDescriptor> =
        files.filterNot { it.id in completedFileIds }

    fun durableBytes(fileId: String): Long =
        checkpoints[fileId]?.bytesDurable ?: 0L
}

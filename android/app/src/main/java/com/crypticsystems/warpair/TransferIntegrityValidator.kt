package com.crypticsystems.warpair

/**
 * Validates the ordering and identity of a single-file transfer before bytes are committed.
 * This class is deliberately platform-independent so its invariants can be unit tested.
 */
internal class TransferIntegrityValidator(
    private val sessionId: String,
    private val expectedSize: Long,
    initialBytes: Long = 0,
    initialSequence: Long = 0,
    private val maxChunkBytes: Int = 64 * 1024,
) {
    var bytesReceived: Long = initialBytes
        private set
    private var expectedSequence: Long = initialSequence
    private var finalChunkSeen = false

    init {
        require(expectedSize >= 0)
        require(initialBytes in 0..expectedSize)
        require(initialSequence >= 0)
        require(maxChunkBytes > 0)
    }

    fun acceptChunk(
        chunkSessionId: String,
        fileId: Int,
        sequence: Long,
        dataSize: Int,
        isLast: Boolean,
    ) {
        check(!finalChunkSeen) { "received data after final chunk" }
        check(chunkSessionId == sessionId) { "chunk session mismatch" }
        check(fileId == 1) { "unexpected file id $fileId" }
        check(sequence == expectedSequence) {
            "chunk sequence mismatch: expected $expectedSequence, got $sequence"
        }
        check(dataSize in 0..maxChunkBytes) { "chunk exceeds maximum size" }
        check(bytesReceived <= expectedSize - dataSize) { "received more bytes than offered" }
        bytesReceived += dataSize
        expectedSequence++
        finalChunkSeen = isLast
    }

    fun verifyComplete(
        completeSessionId: String,
        fileId: Int,
        expectedChecksum: ByteArray,
        actualChecksum: ByteArray,
    ) {
        check(finalChunkSeen) { "completion arrived before final chunk" }
        check(completeSessionId == sessionId) { "completion session mismatch" }
        check(fileId == 1) { "completion file id mismatch" }
        check(bytesReceived == expectedSize) {
            "file size mismatch: expected $expectedSize, received $bytesReceived"
        }
        check(expectedChecksum.size == 32 && actualChecksum.size == 32) {
            "invalid BLAKE3 checksum length"
        }
        check(actualChecksum.contentEquals(expectedChecksum)) {
            "file checksum mismatch; received file discarded"
        }
    }
}

package com.crypticsystems.warpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferIntegrityValidatorTest {
    @Test
    fun acceptsOrderedTransferWithMatchingCompletion() {
        val validator = TransferIntegrityValidator("sid-1", expectedSize = 5)
        validator.acceptChunk("sid-1", 1, 0, 5, isLast = true)
        validator.verifyComplete("sid-1", 1, ByteArray(32) { 1 }, ByteArray(32) { 1 })
        assertEquals(5L, validator.bytesReceived)
    }

    @Test
    fun rejectsChunkFromDifferentSession() {
        val validator = TransferIntegrityValidator("sid-1", expectedSize = 1)
        assertThrows(IllegalStateException::class.java) {
            validator.acceptChunk("sid-2", 1, 0, 1, isLast = true)
        }
    }

    @Test
    fun rejectsOutOfOrderChunk() {
        val validator = TransferIntegrityValidator("sid-1", expectedSize = 2)
        assertThrows(IllegalStateException::class.java) {
            validator.acceptChunk("sid-1", 1, 1, 1, isLast = false)
        }
    }

    @Test
    fun rejectsCompletionBeforeFinalChunk() {
        val validator = TransferIntegrityValidator("sid-1", expectedSize = 1)
        assertThrows(IllegalStateException::class.java) {
            validator.verifyComplete("sid-1", 1, ByteArray(32) { 9 }, ByteArray(32) { 9 })
        }
    }

    @Test
    fun rejectsWrongSizeAndChecksum() {
        val sizeValidator = TransferIntegrityValidator("sid-1", expectedSize = 2)
        sizeValidator.acceptChunk("sid-1", 1, 0, 1, isLast = true)
        assertThrows(IllegalStateException::class.java) {
            sizeValidator.verifyComplete("sid-1", 1, byteArrayOf(9), byteArrayOf(9))
        }

        val hashValidator = TransferIntegrityValidator("sid-1", expectedSize = 1)
        hashValidator.acceptChunk("sid-1", 1, 0, 1, isLast = true)
        assertThrows(IllegalStateException::class.java) {
            hashValidator.verifyComplete("sid-1", 1, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        }
    }

    @Test
    fun rejectsChunkAfterFinalMarker() {
        val validator = TransferIntegrityValidator("sid-1", expectedSize = 2)
        validator.acceptChunk("sid-1", 1, 0, 1, isLast = true)
        assertThrows(IllegalStateException::class.java) {
            validator.acceptChunk("sid-1", 1, 1, 1, isLast = true)
        }
    }

    @Test
    fun rejectsChunkLargerThanProtocolLimit() {
        val validator = TransferIntegrityValidator("sid-1", expectedSize = 64 * 1024L + 1)
        assertThrows(IllegalStateException::class.java) {
            validator.acceptChunk("sid-1", 1, 0, 64 * 1024 + 1, isLast = false)
        }
    }

    @Test
    fun resumesFromVerifiedChunkBoundary() {
        val validator = TransferIntegrityValidator(
            sessionId = "sid-1",
            expectedSize = 64 * 1024L + 3,
            initialBytes = 64 * 1024L,
            initialSequence = 1,
        )
        validator.acceptChunk("sid-1", 1, 1, 3, isLast = true)
        validator.verifyComplete("sid-1", 1, ByteArray(32) { 7 }, ByteArray(32) { 7 })
        assertEquals(64 * 1024L + 3, validator.bytesReceived)
    }
}

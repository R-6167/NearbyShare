package com.crypticsystems.warpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferRecoveryStateTest {
    private fun initial() = TransferRecoveryState(
        transferId = "transfer-123",
        files = listOf(
            TransferFileDescriptor("f1", "video.mp4", 200_000),
            TransferFileDescriptor("f2", "notes.txt", 20),
        ),
    )

    @Test
    fun checkpointSurvivesTransportRetryWithoutChangingLogicalTransfer() {
        val original = initial()
        val checkpointed = original.recordCheckpoint(
            TransferFileCheckpoint("f1", 200_000, 131_072, 2),
        )
        assertNotSame(original, checkpointed)
        assertEquals("transfer-123", checkpointed.transferId)
        assertEquals(original.files, checkpointed.files)
        assertEquals(0L, original.durableBytes("f1"))
        assertEquals(131_072L, checkpointed.durableBytes("f1"))
    }

    @Test
    fun completionRemovesPartialCheckpointAndKeepsOtherFiles() {
        val state = initial()
            .recordCheckpoint(TransferFileCheckpoint("f1", 200_000, 65_536, 1))
            .markCompleted("f1")
        assertEquals(0L, state.durableBytes("f1"))
        assertEquals(listOf("f2"), state.remainingFiles().map { it.id })
        assertEquals(setOf("f1"), state.completedFileIds)
    }

    @Test
    fun rejectsCheckpointWithWrongSizeOrUnknownFile() {
        val state = initial()
        assertThrows(IllegalArgumentException::class.java) {
            state.recordCheckpoint(TransferFileCheckpoint("f1", 200_001, 65_536, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.recordCheckpoint(TransferFileCheckpoint("missing", 10, 0, 0))
        }
    }

    @Test
    fun rejectsNonChunkAlignedOrMismatchedSequence() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferFileCheckpoint("f1", 200_000, 65_535, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferFileCheckpoint("f1", 200_000, 131_072, 1)
        }
    }

    @Test
    fun rejectsDuplicateIdsAndCheckpointForCompletedFile() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferRecoveryState(
                "bad",
                listOf(
                    TransferFileDescriptor("same", "a", 1),
                    TransferFileDescriptor("same", "b", 2),
                ),
            )
        }
        val completed = initial().markCompleted("f1")
        assertThrows(IllegalArgumentException::class.java) {
            completed.recordCheckpoint(TransferFileCheckpoint("f1", 200_000, 65_536, 1))
        }
    }

    @Test
    fun rejectsCheckpointRegressionAndChunkSizeChanges() {
        val checkpointed = initial().recordCheckpoint(
            TransferFileCheckpoint("f1", 200_000, 131_072, 2),
        )
        assertThrows(IllegalArgumentException::class.java) {
            checkpointed.recordCheckpoint(TransferFileCheckpoint("f1", 200_000, 65_536, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkpointed.recordCheckpoint(
                TransferFileCheckpoint("f1", 200_000, 196_608, 3, chunkSizeBytes = 32 * 1024),
            )
        }
    }

    @Test
    fun rejectsStateContainingCheckpointForCompletedFile() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferRecoveryState(
                transferId = "inconsistent",
                files = listOf(TransferFileDescriptor("f1", "video.mp4", 200_000)),
                checkpoints = mapOf(
                    "f1" to TransferFileCheckpoint("f1", 200_000, 65_536, 1),
                ),
                completedFileIds = setOf("f1"),
            )
        }
    }

}

package com.crypticsystems.warpair

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferFailoverCoordinatorTest {
    private fun initial() = TransferRecoveryState(
        transferId = "logical-transfer-7",
        files = listOf(TransferFileDescriptor("video", "clip.mp4", 200_000)),
    )

    @Test
    fun fallbackReceivesLatestDurableCheckpointAndCompletesSameTransfer() {
        val coordinator = TransferFailoverCoordinator(
            initial(),
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.WIFI_LAN, TransferTransport.BLUETOOTH),
        )
        var bluetoothStartedWith: TransferRecoveryState? = null
        val result = coordinator.execute(
            mapOf(
                TransferTransport.WIFI_DIRECT to TransportAttempt { state, update ->
                    update(state.recordCheckpoint(TransferFileCheckpoint("video", 200_000, 131_072, 2)))
                    throw IOException("Wi-Fi Direct socket lost")
                },
                TransferTransport.WIFI_LAN to TransportAttempt { _, _ ->
                    throw IOException("LAN peer unreachable")
                },
                TransferTransport.BLUETOOTH to TransportAttempt { state, update ->
                    bluetoothStartedWith = state
                    update(state.markCompleted("video"))
                    true
                },
            ),
        )

        assertEquals("logical-transfer-7", result.transferId)
        assertEquals(setOf("video"), result.completedFileIds)
        assertEquals(131_072L, bluetoothStartedWith?.durableBytes("video"))
        assertEquals(
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.WIFI_LAN),
            coordinator.failures().map { it.transport },
        )
    }

    @Test
    fun routeCannotChangeTransferIdentityOrLoseCheckpoint() {
        val coordinator = TransferFailoverCoordinator(
            initial().recordCheckpoint(TransferFileCheckpoint("video", 200_000, 65_536, 1)),
            listOf(TransferTransport.WIFI_LAN, TransferTransport.BLUETOOTH),
        )
        val error = assertThrows(TransportFailoverException::class.java) {
            coordinator.execute(
                mapOf(
                    TransferTransport.WIFI_LAN to TransportAttempt { state, update ->
                        update(state.copy(transferId = "new-transfer"))
                        true
                    },
                    TransferTransport.BLUETOOTH to TransportAttempt { _, _ -> false },
                ),
            )
        }
        assertEquals(2, error.failures.size)
        assertEquals(65_536L, coordinator.snapshot().durableBytes("video"))
    }

    @Test
    fun cancellationDoesNotTriggerAnotherTransport() {
        val coordinator = TransferFailoverCoordinator(
            initial(),
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.BLUETOOTH),
        )
        var bluetoothCalled = false
        assertThrows(CancellationException::class.java) {
            coordinator.execute(
                mapOf(
                    TransferTransport.WIFI_DIRECT to TransportAttempt { _, _ ->
                        throw LanLink.TransferCancelledException()
                    },
                    TransferTransport.BLUETOOTH to TransportAttempt { _, _ ->
                        bluetoothCalled = true
                        true
                    },
                ),
            )
        }
        assertEquals(false, bluetoothCalled)
        assertEquals(emptyList<TransportAttemptFailure>(), coordinator.failures())
    }

    @Test
    fun allFailuresReportTheRoutesTriedAndRetainProgress() {
        val coordinator = TransferFailoverCoordinator(
            initial(),
            listOf(TransferTransport.WIFI_LAN, TransferTransport.BLUETOOTH),
        )
        val error = assertThrows(TransportFailoverException::class.java) {
            coordinator.execute(
                mapOf(
                    TransferTransport.WIFI_LAN to TransportAttempt { state, update ->
                        update(state.recordCheckpoint(TransferFileCheckpoint("video", 200_000, 65_536, 1)))
                        throw IOException("link dropped")
                    },
                    TransferTransport.BLUETOOTH to TransportAttempt { _, _ ->
                        throw IOException("Bluetooth unavailable")
                    },
                ),
            )
        }
        assertEquals(2, error.failures.size)
        assertEquals(65_536L, coordinator.snapshot().durableBytes("video"))
    }
}

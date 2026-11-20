package com.crypticsystems.warpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferRouteRunnerTest {
    @Test
    fun failedPrimaryRouteFallsBackAndReturnsConfirmedBatch() {
        val runner = TransferRouteRunner(
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.BLUETOOTH),
        )
        val calls = mutableListOf<String>()
        val result = runner.execute(
            attempts = mapOf(
                TransferTransport.WIFI_DIRECT to {
                    calls += "wifi"
                    throw java.io.IOException("group owner unreachable")
                },
                TransferTransport.BLUETOOTH to {
                    calls += "bluetooth"
                    "BATCH OK (sender) — 1"
                },
            ),
            control = LanLink.TransferControl(),
        )
        assertEquals(listOf("wifi", "bluetooth"), calls)
        assertEquals("BATCH OK (sender) — 1", result)
    }

    @Test
    fun cancellationNeverStartsFallbackRoute() {
        val runner = TransferRouteRunner(
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.BLUETOOTH),
        )
        val calls = mutableListOf<String>()
        assertThrows(LanLink.TransferCancelledException::class.java) {
            runner.execute(
                attempts = mapOf(
                    TransferTransport.WIFI_DIRECT to {
                        calls += "wifi"
                        throw LanLink.TransferCancelledException()
                    },
                    TransferTransport.BLUETOOTH to {
                        calls += "bluetooth"
                        "BATCH OK"
                    },
                ),
                control = LanLink.TransferControl(),
            )
        }
        assertEquals(listOf("wifi"), calls)
    }

    @Test
    fun incompleteBatchIsNotReportedAsSuccessAndFallsBack() {
        val runner = TransferRouteRunner(
            listOf(TransferTransport.BLUETOOTH, TransferTransport.WIFI_DIRECT),
        )
        val result = runner.execute(
            attempts = mapOf(
                TransferTransport.BLUETOOTH to { "BATCH FINISHED WITH ISSUES" },
                TransferTransport.WIFI_DIRECT to { "BATCH OK (sender) — 1" },
            ),
            control = LanLink.TransferControl(),
        )
        assertEquals("BATCH OK (sender) — 1", result)
    }
}

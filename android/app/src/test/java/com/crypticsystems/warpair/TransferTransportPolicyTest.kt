package com.crypticsystems.warpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferTransportPolicyTest {
    @Test
    fun prefersWifiAndSuggestsBluetoothForSmallFiles() {
        val plan = TransferTransportPolicy.plan(
            fileSizeBytes = 2L * 1024 * 1024,
            availability = TransportAvailability(wifiLan = true, bluetooth = true),
        )
        assertEquals(TransferTransport.WIFI_LAN, plan.primary)
        assertEquals(TransferTransport.BLUETOOTH, plan.suggestedForSmallFile)
        assertEquals(TransferTransport.BLUETOOTH, plan.fallbackAfter(TransferTransport.WIFI_LAN))
    }

    @Test
    fun largeFilesDoNotGetBluetoothSuggestion() {
        val plan = TransferTransportPolicy.plan(
            fileSizeBytes = TransferTransportPolicy.SMALL_FILE_LIMIT_BYTES + 1,
            availability = TransportAvailability(wifiLan = true, bluetooth = true),
        )
        assertNull(plan.suggestedForSmallFile)
        assertEquals(TransferTransport.WIFI_LAN, plan.primary)
    }

    @Test
    fun usesBluetoothWhenItIsTheOnlyAvailableTransport() {
        val plan = TransferTransportPolicy.plan(
            fileSizeBytes = 100,
            availability = TransportAvailability(bluetooth = true),
        )
        assertEquals(listOf(TransferTransport.BLUETOOTH), plan.candidates)
        assertEquals(TransferTransport.BLUETOOTH, plan.primary)
    }

    @Test
    fun prefersWifiDirectBeforeSharedWifiWhenBothAreAvailable() {
        val plan = TransferTransportPolicy.plan(
            fileSizeBytes = 50_000_000,
            availability = TransportAvailability(wifiLan = true, wifiDirect = true),
        )
        assertEquals(
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.WIFI_LAN),
            plan.candidates,
        )
    }

    @Test
    fun failoverMovesForwardWithoutResettingAttemptHistory() {
        val sequence = TransportFailoverSequence(
            listOf(TransferTransport.WIFI_DIRECT, TransferTransport.WIFI_LAN, TransferTransport.BLUETOOTH),
        )
        assertEquals(TransferTransport.WIFI_DIRECT, sequence.current())
        assertEquals(TransferTransport.WIFI_LAN, sequence.markFailed(TransferTransport.WIFI_DIRECT))
        assertEquals(TransferTransport.BLUETOOTH, sequence.markFailed(TransferTransport.WIFI_LAN))
        assertNull(sequence.markFailed(TransferTransport.BLUETOOTH))
        assertEquals(
            setOf(TransferTransport.WIFI_DIRECT, TransferTransport.WIFI_LAN, TransferTransport.BLUETOOTH),
            sequence.attempted(),
        )
    }

    @Test
    fun rejectsNegativeFileSizesAndUnknownFailures() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferTransportPolicy.plan(-1, TransportAvailability(wifiLan = true))
        }
        val sequence = TransportFailoverSequence(listOf(TransferTransport.WIFI_LAN))
        assertThrows(IllegalArgumentException::class.java) {
            sequence.markFailed(TransferTransport.BLUETOOTH)
        }
    }
}

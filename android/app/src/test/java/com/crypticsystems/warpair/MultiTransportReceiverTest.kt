package com.crypticsystems.warpair

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTransportReceiverTest {
    private class TestConnection : TransferConnection {
        override val input = ByteArrayInputStream(byteArrayOf())
        override val output = ByteArrayOutputStream()
        override fun close() {}
    }

    private class FakeAcceptor(
        override val transportName: String,
        private val action: () -> TransferConnection,
    ) : ReceiverAcceptor {
        val cancelled = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        override fun accept(): TransferConnection = action()
        override fun cancel() { cancelled.set(true) }
        override fun close() { closed.set(true) }
    }

    @Test
    fun firstAcceptedTransportWinsAndLosersAreCancelled() {
        val bothStarted = CountDownLatch(2)
        val expected = TestConnection()
        val bluetooth = FakeAcceptor("BLUETOOTH") {
            bothStarted.countDown()
            check(bothStarted.await(2, TimeUnit.SECONDS))
            expected
        }
        val lan = FakeAcceptor("WIFI_LAN") {
            bothStarted.countDown()
            throw IOException("listener unavailable")
        }
        val receiver = MultiTransportReceiver(listOf(bluetooth, lan))
        val actual = receiver.accept(LanLink.TransferControl())
        assertSame(expected, actual)
        assertTrue(lan.cancelled.get())
        receiver.close()
        assertTrue(bluetooth.closed.get())
        assertTrue(lan.closed.get())
    }

    @Test
    fun failedTransportDoesNotPreventAnotherListenerFromAccepting() {
        val expected = TestConnection()
        val failed = FakeAcceptor("BLUETOOTH") { throw IOException("radio unavailable") }
        val lan = FakeAcceptor("WIFI_LAN") { expected }
        val receiver = MultiTransportReceiver(listOf(failed, lan))
        assertSame(expected, receiver.accept(LanLink.TransferControl()))
        receiver.close()
    }

    @Test
    fun duplicateTransportNamesAreRejected() {
        val one = FakeAcceptor("WIFI_LAN") { TestConnection() }
        val two = FakeAcceptor("WIFI_LAN") { TestConnection() }
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MultiTransportReceiver(listOf(one, two))
        }
        assertEquals("receiver transport names must be unique", error.message)
    }
}

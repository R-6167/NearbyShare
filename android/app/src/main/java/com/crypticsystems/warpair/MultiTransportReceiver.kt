package com.crypticsystems.warpair

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A receiver-side acceptor. cancel() must stop only a pending accept; close()
 * releases the adapter after the selected connection has finished its protocol.
 */
interface ReceiverAcceptor : Closeable {
    val transportName: String
    fun accept(): TransferConnection
    fun cancel()
}

/**
 * Races independent receiver transports and hands the first established stream
 * to the one shared authenticated LanLink protocol. Failed listeners do not
 * prevent another listener from accepting; losing listeners are stopped.
 */
class MultiTransportReceiver(
    private val acceptors: List<ReceiverAcceptor>,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(acceptors.size.coerceAtLeast(1))
    @Volatile private var winner: ReceiverAcceptor? = null

    init {
        require(acceptors.isNotEmpty()) { "at least one receiver transport is required" }
        require(acceptors.map { it.transportName }.distinct().size == acceptors.size) {
            "receiver transport names must be unique"
        }
    }

    @Throws(IOException::class)
    fun accept(control: LanLink.TransferControl): TransferConnection {
        check(!closed.get()) { "receiver is closed" }
        val completion: CompletionService<Accepted> = ExecutorCompletionService(executor)
        val futures = mutableListOf<Future<Accepted>>()
        acceptors.forEach { acceptor ->
            futures += completion.submit<Accepted> {
                try {
                    Accepted(acceptor, acceptor.accept(), null)
                } catch (failure: Throwable) {
                    Accepted(acceptor, null, failure)
                }
            }
        }

        val failures = mutableListOf<String>()
        var remaining = futures.size
        try {
            while (remaining > 0) {
                control.check()
                check(!closed.get()) { "receiver was closed while waiting for a peer" }
                val completed = completion.poll(250, TimeUnit.MILLISECONDS) ?: continue
                remaining--
                val result = completed.get()
                val connection = result.connection
                if (connection != null) {
                    winner = result.acceptor
                    acceptors.filter { it !== result.acceptor }.forEach { it.cancel() }
                    futures.filterNot { it.isDone }.forEach { it.cancel(true) }
                    return connection
                }
                failures += "${result.acceptor.transportName}: ${result.failure?.message ?: "connection failed"}"
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for a receiver transport.", interrupted)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw LanLink.TransferCancelledException()
        } finally {
            if (winner == null) acceptors.forEach { it.cancel() }
        }

        throw IOException(
            if (failures.isEmpty()) "No receiver transport accepted a peer."
            else "All receiver transports failed: ${failures.joinToString("; ")}",
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptors.forEach { acceptor ->
            try { acceptor.cancel() } catch (_: Throwable) {}
        }
        executor.shutdownNow()
        acceptors.forEach { acceptor ->
            try { acceptor.close() } catch (_: Throwable) {}
        }
    }

    private data class Accepted(
        val acceptor: ReceiverAcceptor,
        val connection: TransferConnection?,
        val failure: Throwable?,
    )
}

/** One wildcard TCP listener covers ordinary LAN, SoftAP and an established Wi-Fi Direct group. */
class TcpReceiverAcceptor(
    port: Int,
    private val onListening: (Int) -> Unit = {},
) : ReceiverAcceptor {
    override val transportName: String = "WIFI_LAN"
    private val server = ServerSocket()
    private val stopped = AtomicBoolean(false)

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        server.soTimeout = 1_000
        onListening(server.localPort)
    }

    override fun accept(): TransferConnection {
        while (!stopped.get()) {
            try {
                val socket = server.accept()
                socket.soTimeout = 180_000
                return SocketTransferConnection(socket)
            } catch (_: SocketTimeoutException) {
                if (Thread.currentThread().isInterrupted) throw IOException("TCP accept interrupted")
            } catch (failure: IOException) {
                if (stopped.get()) throw IOException("TCP receiver stopped", failure)
                throw failure
            }
        }
        throw IOException("TCP receiver stopped")
    }

    override fun cancel() {
        if (stopped.compareAndSet(false, true)) {
            try { server.close() } catch (_: Throwable) {}
        }
    }

    override fun close() = cancel()
}

/** Bluetooth acceptor whose cancellation closes the RFCOMM server, not a selected peer socket. */
class BluetoothReceiverAcceptor(
    private val adapter: BluetoothLink,
) : ReceiverAcceptor {
    override val transportName: String = "BLUETOOTH"

    override fun accept(): TransferConnection = adapter.acceptPeer()

    override fun cancel() = adapter.cancelAccept()

    override fun close() = adapter.close()
}

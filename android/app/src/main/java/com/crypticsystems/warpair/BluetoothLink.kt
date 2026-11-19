package com.crypticsystems.warpair

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Secure Bluetooth Classic RFCOMM transport adapter.
 *
 * It deliberately does not own discovery, pairing UI, transfer identity or file
 * checkpoints. Devices must be paired by Android first; the shared LanLink
 * handshake and resume protocol runs over the returned full-duplex stream.
 */
class BluetoothLink(context: Context) : AutoCloseable {
    data class Peer(val name: String, val address: String)

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val closed = AtomicBoolean(false)
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null

    fun isSupported(): Boolean = adapter != null

    fun isEnabled(): Boolean {
        ensureSupported()
        return try {
            adapter!!.isEnabled
        } catch (error: SecurityException) {
            throw permissionError("check Bluetooth state", error)
        }
    }

    /** Returns already-bonded devices; no background scanning is started. */
    fun bondedPeers(): List<Peer> {
        ensureReady()
        return try {
            adapter!!.bondedDevices.orEmpty()
                .map { Peer(it.name?.takeIf(String::isNotBlank) ?: "Bluetooth device", it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (error: SecurityException) {
            throw permissionError("list paired devices", error)
        }
    }

    /**
     * Wait for one authenticated WarPair peer. The operating system handles
     * Bluetooth pairing/encryption; LanLink still performs its own PIN handshake.
     */
    @Throws(IOException::class)
    fun acceptPeer(): TransferConnection {
        ensureReady()
        val listening = try {
            adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (error: SecurityException) {
            throw permissionError("start Bluetooth receiver", error)
        } catch (error: Throwable) {
            throw IOException("Could not start Bluetooth receiver: ${error.message}", error)
        }
        server = listening
        try {
            val socket = listening.accept()
            pendingSocket = socket
            if (closed.get()) {
                socket.close()
                throw IOException("Bluetooth adapter was closed while waiting for a peer")
            }
            return BluetoothTransferConnection(socket) { pendingSocket = null }
        } catch (error: SecurityException) {
            throw permissionError("accept Bluetooth peer", error)
        } catch (error: IOException) {
            throw IOException("Bluetooth connection failed: ${error.message}", error)
        } finally {
            server = null
            try { listening.close() } catch (_: Throwable) {}
        }
    }

    /** Connect to a device already paired in Android Bluetooth settings. */
    @Throws(IOException::class)
    fun connect(peerAddress: String): TransferConnection {
        ensureReady()
        require(peerAddress.isNotBlank()) { "Bluetooth peer address is required" }
        val device = try {
            adapter!!.getRemoteDevice(peerAddress)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid Bluetooth peer address.", error)
        } catch (error: SecurityException) {
            throw permissionError("select Bluetooth peer", error)
        }
        val socket = try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (error: SecurityException) {
            throw permissionError("prepare Bluetooth connection", error)
        } catch (error: Throwable) {
            throw IOException("Could not prepare Bluetooth connection: ${error.message}", error)
        }
        pendingSocket = socket
        try {
            socket.connect()
            if (closed.get()) {
                socket.close()
                throw IOException("Bluetooth adapter was closed while connecting")
            }
            return BluetoothTransferConnection(socket) { pendingSocket = null }
        } catch (error: SecurityException) {
            try { socket.close() } catch (_: Throwable) {}
            throw permissionError("connect to Bluetooth peer", error)
        } catch (error: IOException) {
            try { socket.close() } catch (_: Throwable) {}
            throw IOException(
                "Could not connect to $peerAddress. Confirm the phones are paired, Bluetooth is enabled, " +
                    "and the other phone has started WarPair Bluetooth receive. ${error.message}",
                error,
            )
        } finally {
            if (socket.isConnected) pendingSocket = socket
            else pendingSocket = null
        }
    }

    private fun ensureSupported() {
        check(adapter != null) { "This device does not support Bluetooth." }
    }

    private fun ensureReady() {
        ensureSupported()
        check(!closed.get()) { "Bluetooth adapter is closed" }
        if (!isEnabled()) throw IOException("Bluetooth is turned off. Enable it and retry.")
    }

    private fun permissionError(action: String, cause: SecurityException) =
        SecurityException("Bluetooth permission is required to $action. Allow Nearby devices, then retry.", cause)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { server?.close() } catch (_: Throwable) {}
        try { pendingSocket?.close() } catch (_: Throwable) {}
        server = null
        pendingSocket = null
    }

    private class BluetoothTransferConnection(
        private val socket: BluetoothSocket,
        private val onClose: () -> Unit,
    ) : TransferConnection {
        override val input get() = socket.inputStream
        override val output get() = socket.outputStream
        override fun close() {
            try { socket.close() } finally { onClose() }
        }
    }

    companion object {
        private const val SERVICE_NAME = "WarPair"
        // Stable application UUID shared by all WarPair installations.
        val SERVICE_UUID: UUID = UUID.fromString("a8c2f4d0-6e5b-4d91-9a3f-575250414952")
    }
}

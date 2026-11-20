package com.crypticsystems.warpair

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Secure Bluetooth Classic RFCOMM transport adapter.
 *
 * It owns Bluetooth Classic discovery and secure RFCOMM sockets. Android handles
 * the system pairing prompt when needed; the shared LanLink handshake and resume
 * protocol runs over the returned full-duplex stream.
 */
class BluetoothLink(context: Context) : AutoCloseable {
    data class Peer(val name: String, val address: String, val bonded: Boolean = false)

    interface DiscoveryListener {
        fun onPeersChanged(peers: List<Peer>) {}
        fun onFinished() {}
        fun onError(message: String) {}
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val closed = AtomicBoolean(false)
    private val acceptCancelled = AtomicBoolean(false)
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile private var discoveryListener: DiscoveryListener? = null
    @Volatile private var receiverRegistered = false
    private val discoveredPeers = linkedMapOf<String, Peer>()
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    try {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        if (device != null) {
                            val peer = Peer(device.name?.takeIf(String::isNotBlank) ?: "Bluetooth device", device.address, false)
                            synchronized(discoveredPeers) {
                                discoveredPeers[peer.address] = peer
                                discoveryListener?.onPeersChanged(discoveredPeers.values.sortedBy { it.name.lowercase() })
                            }
                        }
                    } catch (error: SecurityException) {
                        discoveryListener?.onError(permissionError("read discovered Bluetooth devices", error).message.orEmpty())
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    cancelDiscoveryReceiver()
                    discoveryListener?.onFinished()
                }
            }
        }
    }

    fun isSupported(): Boolean = adapter != null

    /** Current Android Bluetooth adapter name, used only as a short-lived QR discovery hint. */
    fun localDeviceName(): String? = try {
        adapter?.name?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

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
                .map { Peer(it.name?.takeIf(String::isNotBlank) ?: "Bluetooth device", it.address, true) }
                .sortedBy { it.name.lowercase() }
        } catch (error: SecurityException) {
            throw permissionError("list paired devices", error)
        }
    }

    /** Discover nearby devices and merge them with the already-paired list. */
    fun startDiscovery(listener: DiscoveryListener) {
        ensureReady()
        stopDiscovery()
        discoveryListener = listener
        synchronized(discoveredPeers) {
            discoveredPeers.clear()
            bondedPeers().forEach { discoveredPeers[it.address] = it }
            listener.onPeersChanged(discoveredPeers.values.sortedBy { it.name.lowercase() })
        }
        try {
            if (adapter!!.isDiscovering) adapter.cancelDiscovery()
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(discoveryReceiver, filter)
            }
            receiverRegistered = true
            if (!adapter.startDiscovery()) {
                cancelDiscoveryReceiver()
                listener.onError("Android could not start Bluetooth discovery. Check that Bluetooth is enabled and try again.")
            }
        } catch (error: SecurityException) {
            cancelDiscoveryReceiver()
            listener.onError(permissionError("discover nearby Bluetooth devices", error).message.orEmpty())
        } catch (error: Throwable) {
            cancelDiscoveryReceiver()
            listener.onError("Bluetooth discovery failed: ${error.message}")
        }
    }

    fun stopDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: Throwable) {}
        cancelDiscoveryReceiver()
    }

    private fun cancelDiscoveryReceiver() {
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(discoveryReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
    }

    /**
     * Wait for one authenticated WarPair peer. The operating system handles
     * Bluetooth pairing/encryption; LanLink still performs its own PIN handshake.
     */
    @Throws(IOException::class)
    fun acceptPeer(): TransferConnection {
        ensureReady()
        if (acceptCancelled.get()) throw IOException("Bluetooth accept was cancelled")
        val listening = try {
            adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (error: SecurityException) {
            throw permissionError("start Bluetooth receiver", error)
        } catch (error: Throwable) {
            throw IOException("Could not start Bluetooth receiver: ${error.message}", error)
        }
        server = listening
        if (acceptCancelled.get()) {
            try { listening.close() } catch (_: Throwable) {}
            server = null
            throw IOException("Bluetooth accept was cancelled")
        }
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

    /** Connect to a discovered or already-paired device; Android may prompt for pairing. */
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
        // Android explicitly recommends cancelling discovery before RFCOMM connect.
        // Discovery keeps the Bluetooth radio busy and can make socket connection stall or
        // fail even after the peer has been found. This runs on the transfer worker thread.
        try {
            adapter!!.cancelDiscovery()
        } catch (error: SecurityException) {
            throw permissionError("stop Bluetooth discovery before connecting", error)
        } catch (error: Throwable) {
            throw IOException("Could not stop Bluetooth discovery before connecting: ${error.message}", error)
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

    /** Stop a pending RFCOMM accept without closing a socket already handed to LanLink. */
    fun cancelAccept() {
        acceptCancelled.set(true)
        val listening = server
        server = null
        try { listening?.close() } catch (_: Throwable) {}
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
        stopDiscovery()
        try { server?.close() } catch (_: Throwable) {}
        try { pendingSocket?.close() } catch (_: Throwable) {}
        server = null
        pendingSocket = null
        discoveryListener = null
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

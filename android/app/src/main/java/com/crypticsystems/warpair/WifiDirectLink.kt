package com.crypticsystems.warpair

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Wi-Fi Direct discovery/group adapter.
 *
 * This class owns P2P discovery and group negotiation only. Once a group exists,
 * the group owner address and TCP socket are handed to the same authenticated
 * LanLink protocol used by LAN and SoftAP. No file state is stored in this class.
 */
class WifiDirectLink(context: Context) : AutoCloseable {
    data class Peer(
        val name: String,
        val address: String,
        val status: Int,
    )

    data class Connection(
        val groupFormed: Boolean,
        val isGroupOwner: Boolean,
        val groupOwnerHost: String?,
    )

    interface Listener {
        fun onPeersChanged(peers: List<Peer>) {}
        fun onConnectionChanged(connection: Connection) {}
        fun onP2pEnabled(enabled: Boolean) {}
        fun onStatus(message: String) {}
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, Looper.getMainLooper()) {
        listener?.onStatus("Wi-Fi Direct channel disconnected. Reinitialize before retrying.")
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var listener: Listener? = null
    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    listener?.onP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    listener?.onStatus(
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            "Wi-Fi Direct is enabled."
                        } else {
                            "Enable Wi-Fi to use Wi-Fi Direct."
                        },
                    )
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.registerReceiver(
                    appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (t: Throwable) {
            listener?.onStatus("Could not register Wi-Fi Direct receiver: ${t.message}")
        }
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    fun discoverPeers() {
        ensureOpen()
        try {
            manager.discoverPeers(channel, actionListener("Discovery started.") {
                "Wi-Fi Direct discovery failed: $it"
            })
        } catch (t: SecurityException) {
            listener?.onStatus(permissionHint(t))
        } catch (t: Throwable) {
            listener?.onStatus("Wi-Fi Direct discovery error: ${t.message}")
        }
    }

    fun stopPeerDiscovery() {
        if (closed.get()) return
        try {
            manager.stopPeerDiscovery(channel, actionListener("Discovery stopped.") {
                "Could not stop Wi-Fi Direct discovery: $it"
            })
        } catch (t: Throwable) {
            listener?.onStatus("Could not stop Wi-Fi Direct discovery: ${t.message}")
        }
    }

    fun connect(peerAddress: String, preferGroupOwner: Boolean = false) {
        ensureOpen()
        require(peerAddress.isNotBlank()) { "peer address is required" }
        val config = WifiP2pConfig().apply {
            deviceAddress = peerAddress
            groupOwnerIntent = if (preferGroupOwner) 15 else 0
        }
        try {
            manager.connect(channel, config, actionListener("Connecting to Wi-Fi Direct peer…") {
                "Wi-Fi Direct connection request failed: $it"
            })
        } catch (t: SecurityException) {
            listener?.onStatus(permissionHint(t))
        } catch (t: Throwable) {
            listener?.onStatus("Wi-Fi Direct connection error: ${t.message}")
        }
    }

    /** Create a group when this phone is explicitly selected as the host. */
    fun createGroup() {
        ensureOpen()
        try {
            manager.createGroup(channel, actionListener("Creating Wi-Fi Direct group…") {
                "Could not create Wi-Fi Direct group: $it"
            })
        } catch (t: SecurityException) {
            listener?.onStatus(permissionHint(t))
        } catch (t: Throwable) {
            listener?.onStatus("Wi-Fi Direct group error: ${t.message}")
        }
    }

    fun removeGroup() {
        if (closed.get()) return
        try {
            manager.removeGroup(channel, actionListener("Leaving Wi-Fi Direct group.") {
                "Could not remove Wi-Fi Direct group: $it"
            })
        } catch (t: Throwable) {
            listener?.onStatus("Could not remove Wi-Fi Direct group: ${t.message}")
        }
    }

    fun requestPeers() {
        if (closed.get()) return
        try {
            manager.requestPeers(channel) { list: WifiP2pDeviceList ->
                val peers = list.deviceList.map { device: WifiP2pDevice ->
                    Peer(device.deviceName.orEmpty(), device.deviceAddress.orEmpty(), device.status)
                }.sortedBy { it.name.lowercase() }
                listener?.onPeersChanged(peers)
                listener?.onStatus(
                    if (peers.isEmpty()) "No Wi-Fi Direct peers found yet."
                    else "Found ${peers.size} Wi-Fi Direct peer(s).",
                )
            }
        } catch (t: SecurityException) {
            listener?.onStatus(permissionHint(t))
        } catch (t: Throwable) {
            listener?.onStatus("Could not list Wi-Fi Direct peers: ${t.message}")
        }
    }

    fun requestConnection() {
        if (closed.get()) return
        try {
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                val host = if (info.groupFormed) info.groupOwnerAddress?.hostAddress else null
                listener?.onConnectionChanged(
                    Connection(info.groupFormed, info.isGroupOwner, host),
                )
                listener?.onStatus(
                    if (!info.groupFormed) "Waiting for a Wi-Fi Direct group."
                    else "Wi-Fi Direct group ready; group owner is ${host ?: "unknown"}.",
                )
            }
        } catch (t: SecurityException) {
            listener?.onStatus(permissionHint(t))
        } catch (t: Throwable) {
            listener?.onStatus("Could not read Wi-Fi Direct connection: ${t.message}")
        }
    }

    /**
     * Open the protocol connection to the group owner. The caller chooses whether
     * to run LanLink.sendConnected or LanLink.hostConnected over the returned link.
     */
    @Throws(IOException::class)
    fun connectToGroupOwner(host: String, port: Int = LanLink.DEFAULT_PORT, timeoutMs: Int = 12_000): TransferConnection {
        require(host.isNotBlank()) { "Wi-Fi Direct group-owner address is unavailable" }
        require(port in 1..65535)
        val socket = Socket()
        try {
            socket.soTimeout = 180_000
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return SocketTransferConnection(socket)
        } catch (t: Throwable) {
            try { socket.close() } catch (_: Throwable) {}
            throw IOException("Could not connect to Wi-Fi Direct group owner $host:$port: ${t.message}", t)
        }
    }

    /** Accept one peer socket on the group owner, then hand it to LanLink. */
    @Throws(IOException::class)
    fun acceptPeer(port: Int = LanLink.DEFAULT_PORT, timeoutMs: Int = 120_000): TransferConnection {
        require(port in 1..65535)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.soTimeout = timeoutMs
            server.bind(InetSocketAddress(port))
            return SocketTransferConnection(server.accept().apply { soTimeout = 180_000 })
        }
    }

    private fun requestConnectionQuietly() = requestConnection()

    private fun actionListener(success: String, failure: (String) -> String) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() { listener?.onStatus(success) }
            override fun onFailure(reason: Int) { listener?.onStatus(failure(reasonText(reason))) }
        }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported"
        else -> "reason $reason"
    }

    private fun permissionHint(error: SecurityException): String =
        "Wi-Fi Direct permission denied. Allow Nearby devices (Android 13+) or Location (older Android), then retry. ${error.message}"

    private fun ensureOpen() {
        check(!closed.get()) { "Wi-Fi Direct adapter is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopPeerDiscovery()
        removeGroup()
        if (registered) {
            try { appContext.unregisterReceiver(receiver) } catch (_: Throwable) {}
            registered = false
        }
        listener = null
    }
}

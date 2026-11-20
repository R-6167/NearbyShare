package com.crypticsystems.warpair

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional Wi-Fi Direct group lifecycle for the shared Host / Receive screen.
 *
 * The regular wildcard TCP receiver already accepts sockets arriving through a
 * formed P2P group, so this class negotiates and owns the group but deliberately
 * does not bind a second TCP socket to the same port.
 */
class WifiDirectGroupSession(
    private val adapter: WifiDirectLink,
    private val statusCallback: (String) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile private var groupCreated = false
    @Volatile private var observer: Closeable? = null

    fun start() {
        check(!closed.get()) { "Wi-Fi Direct group session is closed" }
        if (!started.compareAndSet(false, true)) return

        observer = adapter.addConnectionObserver { connection ->
            if (connection.groupFormed && !connection.groupOwnerHost.isNullOrBlank()) {
                statusCallback(
                    "Wi-Fi Direct group ready. The shared receiver is accepting P2P, LAN, SoftAP and Bluetooth connections.",
                )
            }
        }
        statusCallback("Starting Wi-Fi Direct group negotiation alongside LAN and Bluetooth listening…")
        adapter.createGroup { failure ->
            if (failure != null && !closed.get()) {
                statusCallback("Wi-Fi Direct unavailable for this receive session ($failure). LAN and Bluetooth remain active.")
            } else if (failure == null && !closed.get()) {
                groupCreated = true
                adapter.requestConnection()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { observer?.close() } catch (_: Throwable) {}
        observer = null
        if (groupCreated) adapter.removeGroup()
    }
}

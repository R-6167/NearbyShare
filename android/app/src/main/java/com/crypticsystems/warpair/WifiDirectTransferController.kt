package com.crypticsystems.warpair

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges Android Wi-Fi Direct group negotiation to the existing authenticated
 * LanLink transfer protocol. Call from a worker thread; UI callbacks must be
 * marshalled by the caller.
 */
object WifiDirectTransferController {
    private const val GROUP_TIMEOUT_SECONDS = 45L

    fun host(
        adapter: WifiDirectLink,
        deviceName: String,
        control: LanLink.TransferControl = LanLink.TransferControl(),
        onProgress: LanLink.Progress? = null,
        onPinConfirm: LanLink.PinConfirm,
        onOffer: LanLink.OfferDecision,
        statusCallback: (String) -> Unit = {},
    ): String {
        val connection = awaitGroup(adapter, statusCallback) { adapter.createGroup() }
        require(connection.isGroupOwner) {
            "Wi-Fi Direct group formed, but this device is not the group owner. Join from the other device."
        }
        onStatus("Wi-Fi Direct group ready. Waiting for the other device…")
        val link = adapter.acceptPeer()
        return LanLink.hostConnected(
            connection = link,
            deviceName = deviceName,
            control = control,
            onProgress = onProgress,
            onPinConfirm = onPinConfirm,
            onOffer = onOffer,
        )
    }

    fun join(
        adapter: WifiDirectLink,
        peerAddress: String,
        deviceName: String,
        files: List<LanLink.OutgoingFile>,
        control: LanLink.TransferControl = LanLink.TransferControl(),
        onProgress: LanLink.Progress? = null,
        onPinConfirm: LanLink.PinConfirm,
        statusCallback: (String) -> Unit = {},
    ): String {
        val connection = awaitGroup(adapter, statusCallback) {
            adapter.connect(peerAddress)
        }
        require(!connection.isGroupOwner) {
            "This device became the Wi-Fi Direct group owner. Use Host / Receive on this device."
        }
        val host = connection.groupOwnerHost
            ?: throw IOException("Wi-Fi Direct group owner address was not provided")
        onStatus("Connected to Wi-Fi Direct group. Opening secure transfer…")
        val link = adapter.connectToGroupOwner(host)
        return LanLink.sendConnected(
            connection = link,
            deviceName = deviceName,
            files = files,
            control = control,
            onProgress = onProgress,
            onPinConfirm = onPinConfirm,
        )
    }

    private fun awaitGroup(
        adapter: WifiDirectLink,
        onStatus: (String) -> Unit,
        begin: () -> Unit,
    ): WifiDirectLink.Connection {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiDirectLink.Connection?>()
        val failure = AtomicReference<String?>()
        adapter.setListener(object : WifiDirectLink.Listener {
            override fun onConnectionChanged(connection: WifiDirectLink.Connection) {
                if (connection.groupFormed && !connection.groupOwnerHost.isNullOrBlank()) {
                    result.set(connection)
                    latch.countDown()
                }
            }

            override fun onStatus(message: String) {
                statusCallback(message)
                if (message.contains("failed", ignoreCase = true) ||
                    message.contains("denied", ignoreCase = true) ||
                    message.contains("error", ignoreCase = true) ||
                    message.contains("unsupported", ignoreCase = true)
                ) {
                    failure.set(message)
                    latch.countDown()
                }
            }
        })
        begin()
        if (!latch.await(GROUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Timed out waiting for Wi-Fi Direct group negotiation.")
        }
        failure.get()?.let { throw IOException(it) }
        return result.get() ?: throw IOException("Wi-Fi Direct group was not established.")
    }
}

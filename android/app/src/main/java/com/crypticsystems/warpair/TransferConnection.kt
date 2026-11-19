package com.crypticsystems.warpair

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Established, full-duplex byte stream used by the authenticated transfer protocol.
 * Implementations may wrap TCP, Wi-Fi Direct sockets or Bluetooth RFCOMM sockets.
 */
interface TransferConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** TCP adapter retained for the existing LAN and SoftAP paths. */
internal class SocketTransferConnection(private val socket: Socket) : TransferConnection {
    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    override val input: InputStream
        get() = socket.getInputStream()

    override val output: OutputStream
        get() = socket.getOutputStream()

    override fun close() = socket.close()
}

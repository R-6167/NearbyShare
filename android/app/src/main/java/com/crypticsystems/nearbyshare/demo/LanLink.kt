package com.crypticsystems.nearbyshare.demo

import com.crypticsystems.nearbyshare.NearbyCore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal length-prefixed TCP helpers used by the demo.
 *
 * Wire format matches Rust `framing`: u32 LE length || payload.
 */
object LanLink {
    const val DEFAULT_PORT = 19730

    fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        val frame = NearbyCore.frameEncode(payload)
        out.write(frame)
        out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        require(len >= 0 && len < 16 * 1024 * 1024) { "invalid frame length $len" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return payload
    }

    /** Host: accept one client, run responder handshake, exchange one sealed message. */
    fun hostOnce(deviceName: String, port: Int = DEFAULT_PORT): String {
        ServerSocket(port).use { server ->
            server.soTimeout = 120_000
            val socket = server.accept()
            socket.soTimeout = 60_000
            return socket.use { runSession(it, deviceName, initiator = false) }
        }
    }

    /** Client: connect and run initiator handshake. */
    fun connectOnce(host: String, port: Int, deviceName: String): String {
        val socket = Socket()
        socket.soTimeout = 60_000
        socket.connect(InetSocketAddress(host, port), 15_000)
        return socket.use { runSession(it, deviceName, initiator = true) }
    }

    private fun runSession(socket: Socket, deviceName: String, initiator: Boolean): String {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        NearbyCore.createSession(deviceName, initiator).use { session ->
            val localHello = session.beginHandshake()

            if (initiator) {
                writeFrame(output, localHello)
                val peerHello = readFrame(input)
                session.completeHandshake(peerHello)
            } else {
                val peerHello = readFrame(input)
                session.completeHandshake(peerHello)
                writeFrame(output, localHello)
            }

            val pin = session.pin()
            val peer = session.peerName()

            if (initiator) {
                val sealed = session.seal("ping-from-$deviceName".toByteArray(Charsets.UTF_8))
                writeFrame(output, sealed)
                val replyCipher = readFrame(input)
                val reply = session.open(replyCipher).toString(Charsets.UTF_8)
                return buildString {
                    appendLine("LAN OK (client)")
                    appendLine("peer=$peer")
                    appendLine("pin=$pin")
                    appendLine("reply=$reply")
                }
            } else {
                val cipher = readFrame(input)
                val msg = session.open(cipher).toString(Charsets.UTF_8)
                val sealed = session.seal("ack:$msg".toByteArray(Charsets.UTF_8))
                writeFrame(output, sealed)
                return buildString {
                    appendLine("LAN OK (host)")
                    appendLine("peer=$peer")
                    appendLine("pin=$pin")
                    appendLine("received=$msg")
                }
            }
        }
    }
}

package com.crypticsystems.nearbyshare.demo

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.crypticsystems.nearbyshare.NearbyCore
import java.io.ByteArrayInputStream
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private var picked: PickedFile? = null
    private lateinit var nsd: NsdNearby
    private val mainHandler = Handler(Looper.getMainLooper())

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        try {
            picked = ReceivedFiles.readPickedMeta(this, uri)
            val sizeLabel = if (picked!!.size >= 0) "${picked!!.size} bytes" else "size unknown"
            val cat = ReceivedFiles.categoryFor(picked!!.displayName).folderName
            findViewById<TextView>(R.id.pickedFileText).text =
                "Selected: ${picked!!.displayName} ($sizeLabel) → would land in $cat on receiver"
        } catch (t: Throwable) {
            picked = null
            findViewById<TextView>(R.id.pickedFileText).text =
                "Failed to read file: ${t.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val libraryRoot = ReceivedFiles.ensureLibrary(this)

        nsd = NsdNearby(this)
        nsd.onPeersChanged = { peers ->
            val peersText = findViewById<TextView>(R.id.peersText)
            val hostInput = findViewById<EditText>(R.id.hostInput)
            if (peers.isEmpty()) {
                peersText.text = "Peers: (none yet)"
            } else {
                peersText.text = peers.joinToString("\n") { p ->
                    "${p.deviceName}  ${p.host}:${p.port}"
                }
                if (hostInput.text.isNullOrBlank()) {
                    hostInput.setText(peers.first().host)
                }
            }
        }

        val status = findViewById<TextView>(R.id.statusText)
        val smokeButton = findViewById<Button>(R.id.smokeButton)
        val hostButton = findViewById<Button>(R.id.hostButton)
        val joinButton = findViewById<Button>(R.id.joinButton)
        val pickFileButton = findViewById<Button>(R.id.pickFileButton)
        val discoverButton = findViewById<Button>(R.id.discoverButton)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val payloadInput = findViewById<EditText>(R.id.payloadInput)

        status.text = buildString {
            appendLine("Library:")
            appendLine(libraryRoot.absolutePath)
            appendLine("Folders: Music, Videos, Images, Documents, Archives, Other")
            appendLine()
            appendLine("IPs:")
            append(localIpv4List())
        }

        pickFileButton.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        discoverButton.setOnClickListener {
            nsd.startDiscovery()
            status.text = "Discovering peers on LAN…"
        }

        smokeButton.setOnClickListener {
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton), status) {
                val report = NearbyCore.smokeTest()
                if (report.startsWith("OK")) "Smoke passed\n\n$report" else "Smoke failed\n\n$report"
            }
        }

        hostButton.setOnClickListener {
            val name = nameInput.text?.toString()?.ifBlank { "Host" } ?: "Host"
            nsd.advertise(name, LanLink.DEFAULT_PORT)
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton), status) {
                try {
                    LanLink.hostOnce(
                        deviceName = name,
                        onProgress = progressTo(status),
                        openSink = { fileName ->
                            ReceivedFiles.openIncoming(this@MainActivity, fileName)
                        },
                    )
                } finally {
                    mainHandler.post { nsd.stopAdvertising() }
                }
            }
        }

        joinButton.setOnClickListener {
            val name = nameInput.text?.toString()?.ifBlank { "Client" } ?: "Client"
            val host = hostInput.text?.toString()?.trim().orEmpty()
            if (host.isEmpty()) {
                status.text = "Enter host IP or tap Discover peers."
                return@setOnClickListener
            }
            val localPicked = picked
            val peerPort = nsd.currentPeers().firstOrNull { it.host == host }?.port
                ?: LanLink.DEFAULT_PORT

            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton), status) {
                if (localPicked != null) {
                    ReceivedFiles.openPickedStream(this@MainActivity, localPicked.uri).use { stream ->
                        LanLink.connectOnce(
                            host = host,
                            port = peerPort,
                            deviceName = name,
                            fileName = localPicked.displayName,
                            fileSize = localPicked.size,
                            inputStream = stream,
                            onProgress = progressTo(status),
                        )
                    }
                } else {
                    val text = payloadInput.text?.toString() ?: ""
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    ByteArrayInputStream(bytes).use { stream ->
                        LanLink.connectOnce(
                            host = host,
                            port = peerPort,
                            deviceName = name,
                            fileName = "note.txt",
                            fileSize = bytes.size.toLong(),
                            inputStream = stream,
                            onProgress = progressTo(status),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        nsd.shutdown()
        super.onDestroy()
    }

    private fun progressTo(status: TextView): LanLink.Progress {
        return LanLink.Progress { done, total, phase ->
            val line = if (total > 0) {
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                "$phase  $done/$total ($pct%)"
            } else {
                "$phase  $done bytes"
            }
            mainHandler.post { status.text = line }
        }
    }

    private fun runBg(buttons: List<Button>, status: TextView, block: () -> String) {
        buttons.forEach { it.isEnabled = false }
        status.text = "Working…"
        Thread {
            val result = try {
                block()
            } catch (t: Throwable) {
                "Error: ${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString().take(1500)}"
            }
            runOnUiThread {
                status.text = result
                buttons.forEach { it.isEnabled = true }
            }
        }.start()
    }

    private fun localIpv4List(): String {
        val ips = mutableListOf<String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) {
                        ips += "${ni.name}: ${a.hostAddress}"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return if (ips.isEmpty()) "(none)" else ips.joinToString("\n")
    }
}

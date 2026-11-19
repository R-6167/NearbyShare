package com.crypticsystems.nearbyshare.demo

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.crypticsystems.nearbyshare.NearbyCore
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private var picked: PickedFile? = null

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
            // Not all providers support persistable grants; read still works for this session.
        }
        try {
            picked = ReceivedFiles.readPicked(this, uri)
            findViewById<TextView>(R.id.pickedFileText).text =
                "Selected: ${picked!!.displayName} (${picked!!.bytes.size} bytes)"
        } catch (t: Throwable) {
            picked = null
            findViewById<TextView>(R.id.pickedFileText).text =
                "Failed to read file: ${t.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.statusText)
        val smokeButton = findViewById<Button>(R.id.smokeButton)
        val hostButton = findViewById<Button>(R.id.hostButton)
        val joinButton = findViewById<Button>(R.id.joinButton)
        val pickFileButton = findViewById<Button>(R.id.pickFileButton)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val payloadInput = findViewById<EditText>(R.id.payloadInput)

        status.text =
            "IPs:\n${localIpv4List()}\n\nHost receives & saves files. Join sends a picked file (or text)."

        pickFileButton.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        smokeButton.setOnClickListener {
            runBg(listOf(smokeButton, hostButton, joinButton), status) {
                val report = NearbyCore.smokeTest()
                if (report.startsWith("OK")) "Smoke passed\n\n$report" else "Smoke failed\n\n$report"
            }
        }

        hostButton.setOnClickListener {
            val name = nameInput.text?.toString()?.ifBlank { "Host" } ?: "Host"
            runBg(listOf(smokeButton, hostButton, joinButton), status) {
                LanLink.hostOnce(name) { fileName, data ->
                    ReceivedFiles.saveReceived(this@MainActivity, fileName, data).summary()
                }
            }
        }

        joinButton.setOnClickListener {
            val name = nameInput.text?.toString()?.ifBlank { "Client" } ?: "Client"
            val host = hostInput.text?.toString()?.trim().orEmpty()
            if (host.isEmpty()) {
                status.text = "Enter host IP."
                return@setOnClickListener
            }
            val localPicked = picked
            val fileName: String
            val bytes: ByteArray
            if (localPicked != null) {
                fileName = localPicked.displayName
                bytes = localPicked.bytes
            } else {
                fileName = "note.txt"
                bytes = (payloadInput.text?.toString() ?: "").toByteArray(Charsets.UTF_8)
            }
            runBg(listOf(smokeButton, hostButton, joinButton), status) {
                LanLink.connectOnce(host, LanLink.DEFAULT_PORT, name, fileName, bytes)
            }
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

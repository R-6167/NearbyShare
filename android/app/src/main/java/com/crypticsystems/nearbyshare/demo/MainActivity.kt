package com.crypticsystems.nearbyshare.demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.crypticsystems.nearbyshare.NearbyCore
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.statusText)
        val smokeButton = findViewById<Button>(R.id.smokeButton)
        val hostButton = findViewById<Button>(R.id.hostButton)
        val joinButton = findViewById<Button>(R.id.joinButton)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val payloadInput = findViewById<EditText>(R.id.payloadInput)

        status.text = "IPs:\n${localIpv4List()}\n\nHost waits; Join sends the payload as note.txt."

        smokeButton.setOnClickListener {
            runBg(smokeButton, status) {
                val report = NearbyCore.smokeTest()
                if (report.startsWith("OK")) "Smoke passed\n\n$report" else "Smoke failed\n\n$report"
            }
        }

        hostButton.setOnClickListener {
            val name = nameInput.text?.toString()?.ifBlank { "Host" } ?: "Host"
            runBg(hostButton, status) {
                LanLink.hostOnce(name)
            }
        }

        joinButton.setOnClickListener {
            val name = nameInput.text?.toString()?.ifBlank { "Client" } ?: "Client"
            val host = hostInput.text?.toString()?.trim().orEmpty()
            val payload = payloadInput.text?.toString() ?: ""
            if (host.isEmpty()) {
                status.text = "Enter host IP."
                return@setOnClickListener
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            runBg(joinButton, status) {
                LanLink.connectOnce(host, LanLink.DEFAULT_PORT, name, "note.txt", bytes)
            }
        }
    }

    private fun runBg(button: Button, status: TextView, block: () -> String) {
        button.isEnabled = false
        status.text = "Working…"
        Thread {
            val result = try {
                block()
            } catch (t: Throwable) {
                "Error: ${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString().take(1500)}"
            }
            runOnUiThread {
                status.text = result
                button.isEnabled = true
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

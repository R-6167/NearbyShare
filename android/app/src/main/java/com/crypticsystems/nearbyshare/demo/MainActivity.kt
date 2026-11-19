package com.crypticsystems.nearbyshare.demo

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.crypticsystems.nearbyshare.NearbyCore

/**
 * Demo host: runs the native crypto/session smoke test on device.
 *
 * Uses the stable C ABI via [NearbyCore] (no UniFFI generate required).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.statusText)
        val button = findViewById<Button>(R.id.smokeButton)

        status.text = "Tap the button to run the native core smoke test."

        button.setOnClickListener {
            status.text = "Running…"
            button.isEnabled = false
            Thread {
                val result = runSmoke()
                runOnUiThread {
                    status.text = result
                    button.isEnabled = true
                }
            }.start()
        }
    }

    private fun runSmoke(): String {
        return try {
            val report = NearbyCore.smokeTest()
            if (report.startsWith("OK")) {
                "Native smoke test passed\n\n$report"
            } else {
                "Native smoke test failed\n\n$report"
            }
        } catch (t: Throwable) {
            "Failed to run native smoke test:\n${t.javaClass.simpleName}: ${t.message}\n\n" +
                "Ensure CI/native build produced libnearby_share.so for this ABI.\n" +
                t.stackTraceToString().take(1500)
        }
    }
}

package com.crypticsystems.nearbyshare.demo

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.crypticsystems.nearbyshare.NativeLoad

/**
 * Minimal demo host.
 *
 * After running `scripts/build-android-native.sh`, UniFFI generates
 * `libraryHello()`, `FfiIdentity`, `FfiSession`, etc. under
 * `com.crypticsystems.nearbyshare`. Until then this activity only verifies
 * that `libnearby_share.so` can be loaded.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.statusText)
        val button = findViewById<Button>(R.id.smokeButton)

        button.setOnClickListener {
            status.text = runSmoke()
        }
    }

    private fun runSmoke(): String {
        return try {
            NativeLoad.ensure()
            // Prefer UniFFI helpers when bindings have been generated:
            //   val hello = libraryHello()
            //   val id = FfiIdentity.generate("Android")
            //   "ok: $hello / ${id.fingerprint()}"
            "Native library loaded (libnearby_share).\n" +
                "Generate UniFFI Kotlin bindings to call libraryHello() / FfiSession."
        } catch (t: Throwable) {
            "Failed to load native library:\n${t.message}\n\n" +
                "Run: scripts/build-android-native.sh"
        }
    }
}

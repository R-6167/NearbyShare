package com.crypticsystems.warpair

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Local-only SoftAP so two phones can link without a shared router.
 * Host starts hotspot; client joins Wi‑Fi then TCP to host gateway IP.
 */
class SoftApLink(context: Context) {
    data class Credentials(
        val ssid: String,
        val password: String,
        /** Best-effort host address on the SoftAP subnet (often 192.168.43.1 / 192.168.49.1). */
        val suggestedHostIp: String,
    )

    private val app = context.applicationContext
    private val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun startBlocking(timeoutSec: Long = 20): Credentials {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            error("SoftAP requires Android 8+")
        }
        stop()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Credentials?>(null)
        val error = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())

        main.post {
            try {
                wifi.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                            reservation = res
                            val conf = res.wifiConfiguration
                            val ssid = conf?.SSID?.trim('"') ?: "WarPair"
                            val pass = conf?.preSharedKey?.trim('"') ?: ""
                            result.set(
                                Credentials(
                                    ssid = ssid,
                                    password = pass,
                                    suggestedHostIp = "192.168.43.1",
                                ),
                            )
                            latch.countDown()
                        }

                        override fun onFailed(reason: Int) {
                            error.set("SoftAP failed: $reason")
                            latch.countDown()
                        }

                        override fun onStopped() {
                            reservation = null
                        }
                    },
                    main,
                )
            } catch (t: Throwable) {
                error.set(t.message ?: t.toString())
                latch.countDown()
            }
        }

        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            error("SoftAP start timed out")
        }
        error.get()?.let { error(it) }
        return result.get() ?: error("SoftAP produced no credentials")
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Throwable) {
        }
        reservation = null
    }
}

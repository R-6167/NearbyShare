package com.crypticsystems.warpair

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Offline bootstrap URI:
 * `warpair://connect?host=1.2.3.4&port=19730&ssid=...&pass=...&name=...`
 */
object QrBootstrap {
    data class Payload(
        val host: String,
        val port: Int = LanLink.DEFAULT_PORT,
        val ssid: String? = null,
        val password: String? = null,
        val deviceName: String? = null,
    )

    fun encodeUri(p: Payload): String {
        require(p.host.isNotBlank()) { "host is required" }
        require(p.port in 1..65535) { "port must be between 1 and 65535" }
        val q = buildString {
            append("host=").append(enc(p.host))
            append("&port=").append(p.port)
            p.ssid?.let { append("&ssid=").append(enc(it)) }
            p.password?.let { append("&pass=").append(enc(it)) }
            p.deviceName?.let { append("&name=").append(enc(it)) }
        }
        return "warpair://connect?$q"
    }

    fun parseUri(raw: String): Payload {
        val s = raw.trim()
        require(s.startsWith("warpair://", ignoreCase = true)) { "Not a WarPair QR payload" }
        val query = s.substringAfter('?', missingDelimiterValue = "")
        val map = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isBlank()) continue
            val k = part.substringBefore('=')
            val v = dec(part.substringAfter('=', ""))
            map[k] = v
        }
        val host = map["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("missing host")
        val port = map["port"]?.toIntOrNull() ?: LanLink.DEFAULT_PORT
        require(port in 1..65535) { "port must be between 1 and 65535" }
        return Payload(
            host = host,
            port = port,
            ssid = map["ssid"],
            password = map["pass"],
            deviceName = map["name"],
        )
    }

    fun toBitmap(uri: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun decodeBitmap(bitmap: Bitmap): Payload? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val text = MultiFormatReader().decode(binary).text
            parseUri(text)
        } catch (_: Throwable) {
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
}

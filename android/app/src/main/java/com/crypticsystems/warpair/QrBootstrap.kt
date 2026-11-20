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
import java.security.SecureRandom

/**
 * QR bootstrap and short-lived rendezvous invitations.
 *
 * Legacy connect payloads remain readable for backwards compatibility.
 * V2 invitations describe available routes; they are not credentials and do
 * not replace the authenticated WarPair PIN/session handshake.
 */
object QrBootstrap {
    data class Payload(
        val host: String,
        val port: Int = LanLink.DEFAULT_PORT,
        val ssid: String? = null,
        val password: String? = null,
        val deviceName: String? = null,
    )

    data class Endpoint(val host: String, val port: Int = LanLink.DEFAULT_PORT) {
        init {
            require(host.isNotBlank()) { "LAN endpoint host is required" }
            require(!host.contains(':')) { "Only IPv4 LAN endpoints are supported in this invitation." }
            require(port in 1..65535) { "LAN endpoint port is invalid." }
        }
    }

    data class Invitation(
        val version: Int,
        val sessionId: String,
        val expiresAtEpochSeconds: Long,
        val deviceName: String,
        val routes: Set<String>,
        val lanEndpoints: List<Endpoint>,
        val bluetoothServiceUuid: String? = null,
        val bluetoothDeviceName: String? = null,
        val wifiSsid: String? = null,
        val wifiPassword: String? = null,
    ) {
        fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
            expiresAtEpochSeconds <= nowEpochSeconds
    }

    fun createInvitation(
        deviceName: String,
        routes: Set<String>,
        lanEndpoints: List<Endpoint> = emptyList(),
        bluetoothServiceUuid: String? = null,
        bluetoothDeviceName: String? = null,
        wifiSsid: String? = null,
        wifiPassword: String? = null,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        ttlSeconds: Long = DEFAULT_INVITATION_TTL_SECONDS,
    ): Invitation {
        require(ttlSeconds in 30..MAX_INVITATION_TTL_SECONDS) { "Invitation lifetime must be 30 seconds to 15 minutes." }
        val cleanRoutes = routes.map { it.lowercase() }.toSet()
        require(cleanRoutes.isNotEmpty()) { "At least one connection route must be available." }
        require(cleanRoutes.all { it in SUPPORTED_ROUTES }) { "Invitation contains an unsupported route." }
        require(("lan" in cleanRoutes) == lanEndpoints.isNotEmpty()) {
            "LAN route must have at least one reachable IPv4 endpoint."
        }
        require("bluetooth" !in cleanRoutes || (!bluetoothServiceUuid.isNullOrBlank() && !bluetoothDeviceName.isNullOrBlank())) {
            "Bluetooth route requires its WarPair service UUID and discoverable device name."
        }
        require(lanEndpoints.size <= MAX_LAN_ENDPOINTS)
        require(wifiSsid.isNullOrBlank() == wifiPassword.isNullOrBlank()) { "SoftAP SSID and password must be provided together." }
        require(wifiSsid == null || wifiSsid.length <= 32) { "SoftAP SSID is too long." }
        require(wifiPassword == null || wifiPassword.length <= 63) { "SoftAP password is too long." }
        val random = ByteArray(16).also(secureRandom::nextBytes)
        val sessionId = random.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return Invitation(
            version = INVITATION_VERSION,
            sessionId = sessionId,
            expiresAtEpochSeconds = nowEpochSeconds + ttlSeconds,
            deviceName = deviceName.trim().ifBlank { "WarPair device" }.take(64),
            routes = cleanRoutes,
            lanEndpoints = lanEndpoints.distinct(),
            bluetoothServiceUuid = bluetoothServiceUuid,
            bluetoothDeviceName = bluetoothDeviceName?.trim()?.take(64),
            wifiSsid = wifiSsid?.takeIf { it.isNotBlank() },
            wifiPassword = wifiPassword?.takeIf { it.isNotBlank() },
        )
    }

    fun encodeInvitation(invitation: Invitation): String {
        validateInvitation(invitation, System.currentTimeMillis() / 1000L, checkExpiry = false)
        val query = buildString {
            append("v=").append(invitation.version)
            append("&id=").append(enc(invitation.sessionId))
            append("&exp=").append(invitation.expiresAtEpochSeconds)
            append("&name=").append(enc(invitation.deviceName))
            append("&routes=").append(enc(invitation.routes.sorted().joinToString(",")))
            if (invitation.lanEndpoints.isNotEmpty()) {
                append("&lan=").append(enc(invitation.lanEndpoints.joinToString(",") { "${it.host}:${it.port}" }))
            }
            invitation.bluetoothServiceUuid?.let { append("&bt=").append(enc(it)) }
            invitation.bluetoothDeviceName?.let { append("&btname=").append(enc(it)) }
            invitation.wifiSsid?.let { append("&ssid=").append(enc(it)) }
            invitation.wifiPassword?.let { append("&pass=").append(enc(it)) }
        }
        return "warpair://invite?$query"
    }

    fun parseInvitation(
        raw: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Invitation {
        val s = raw.trim()
        require(s.startsWith("warpair://invite?", ignoreCase = true)) { "Not a WarPair invitation." }
        val map = parseQuery(s.substringAfter('?', ""))
        val version = map["v"]?.toIntOrNull() ?: error("Invitation version is missing.")
        require(version == INVITATION_VERSION) { "This WarPair invitation version is not supported." }
        val sessionId = map["id"].orEmpty()
        val expires = map["exp"]?.toLongOrNull() ?: error("Invitation expiry is missing.")
        val name = map["name"]?.takeIf { it.isNotBlank() } ?: "WarPair device"
        val routes = map["routes"].orEmpty().split(',').filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
        val endpoints = map["lan"].orEmpty().split(',').filter { it.isNotBlank() }.map { entry ->
            val host = entry.substringBeforeLast(':', "")
            val port = entry.substringAfterLast(':', "").toIntOrNull()
                ?: error("Invitation contains an invalid LAN endpoint.")
            Endpoint(host, port)
        }
        val invitation = Invitation(
            version = version,
            sessionId = sessionId,
            expiresAtEpochSeconds = expires,
            deviceName = name,
            routes = routes,
            lanEndpoints = endpoints,
            bluetoothServiceUuid = map["bt"]?.takeIf { it.isNotBlank() },
            bluetoothDeviceName = map["btname"]?.takeIf { it.isNotBlank() },
            wifiSsid = map["ssid"]?.takeIf { it.isNotBlank() },
            wifiPassword = map["pass"]?.takeIf { it.isNotBlank() },
        )
        validateInvitation(invitation, nowEpochSeconds, checkExpiry = true)
        return invitation
    }

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
        require(s.startsWith("warpair://connect?", ignoreCase = true)) { "Not a legacy WarPair connection payload." }
        val map = parseQuery(s.substringAfter('?', ""))
        val host = map["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("missing host")
        val port = map["port"]?.toIntOrNull() ?: LanLink.DEFAULT_PORT
        require(port in 1..65535) { "port must be between 1 and 65535" }
        return Payload(host, port, map["ssid"], map["pass"], map["name"])
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

    fun decodeText(bitmap: Bitmap): String? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binary).text
        } catch (_: Throwable) {
            null
        }
    }

    /** Backwards-compatible decoder for the original LAN connect QR format. */
    fun decodeBitmap(bitmap: Bitmap): Payload? = try {
        decodeText(bitmap)?.let(::parseUri)
    } catch (_: Throwable) {
        null
    }

    private fun validateInvitation(invitation: Invitation, now: Long, checkExpiry: Boolean) {
        require(invitation.version == INVITATION_VERSION) { "Unsupported invitation version." }
        require(invitation.sessionId.matches(Regex("[0-9a-f]{32}"))) { "Invitation session ID is invalid." }
        require(invitation.deviceName.isNotBlank() && invitation.deviceName.length <= 64)
        require(invitation.routes.isNotEmpty() && invitation.routes.all { it in SUPPORTED_ROUTES }) {
            "Invitation has no supported connection routes."
        }
        require(invitation.lanEndpoints.size <= MAX_LAN_ENDPOINTS)
        require(invitation.wifiSsid.isNullOrBlank() == invitation.wifiPassword.isNullOrBlank()) {
            "Invitation SoftAP SSID and password are incomplete."
        }
        require(invitation.wifiSsid == null || invitation.wifiSsid.length <= 32) { "Invitation SoftAP SSID is too long." }
        require(invitation.wifiPassword == null || invitation.wifiPassword.length <= 63) { "Invitation SoftAP password is too long." }
        require(("lan" in invitation.routes) == invitation.lanEndpoints.isNotEmpty()) {
            "Invitation LAN route information is incomplete."
        }
        require("bluetooth" !in invitation.routes || (!invitation.bluetoothServiceUuid.isNullOrBlank() && !invitation.bluetoothDeviceName.isNullOrBlank())) {
            "Invitation Bluetooth route information is incomplete."
        }
        require(invitation.expiresAtEpochSeconds > now || !checkExpiry) { "This WarPair invitation has expired. Ask the receiver for a new QR code." }
        require(invitation.expiresAtEpochSeconds <= now + MAX_INVITATION_TTL_SECONDS || !checkExpiry) {
            "Invitation expiry is outside the allowed window."
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isBlank()) continue
            val key = part.substringBefore('=')
            require(key !in map) { "QR payload contains duplicate fields." }
            map[key] = dec(part.substringAfter('=', ""))
        }
        return map
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())

    private val secureRandom = SecureRandom()

    const val INVITATION_VERSION = 2
    const val DEFAULT_INVITATION_TTL_SECONDS = 300L
    const val MAX_INVITATION_TTL_SECONDS = 900L
    private const val MAX_LAN_ENDPOINTS = 4
    private val SUPPORTED_ROUTES = setOf("lan", "wifi_direct", "bluetooth")
}

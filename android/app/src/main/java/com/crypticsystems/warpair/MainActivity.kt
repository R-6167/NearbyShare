package com.crypticsystems.warpair

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.crypticsystems.warpair.core.NearbyCore
import java.io.ByteArrayInputStream
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private var pickedFiles: List<PickedFile> = emptyList()
    private var lastPeers: List<NsdNearby.Peer> = emptyList()
    private lateinit var nsd: NsdNearby
    private var softAp: SoftApLink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeControl = AtomicReference<LanLink.TransferControl?>(null)

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val loaded = mutableListOf<PickedFile>()
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            try {
                loaded += ReceivedFiles.readPickedMeta(this, uri)
            } catch (t: Throwable) {
                findViewById<TextView>(R.id.pickedFileText).text = "Failed to read: ${t.message}"
                return@registerForActivityResult
            }
        }
        pickedFiles = loaded
        findViewById<TextView>(R.id.pickedFileText).text =
            loaded.joinToString("\n", prefix = "${loaded.size} file(s):\n") { "• ${it.displayName}" }
    }

    private val requestLegacyStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshLibraryStatus() }

    private val requestAllFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshLibraryStatus() }

    private val requestNearbyWifiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        findViewById<TextView>(R.id.statusText)?.text = if (granted) {
            "Nearby Wi‑Fi permission granted. Tap Start SoftAP again."
        } else {
            "Nearby Wi‑Fi permission is required to start a hotspot."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "WarPair"

        val peersList = findViewById<ListView>(R.id.peersList)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val portInput = findViewById<EditText>(R.id.portInput)
        val status = findViewById<TextView>(R.id.statusText)
        val smokeButton = findViewById<Button>(R.id.smokeButton)
        val hostButton = findViewById<Button>(R.id.hostButton)
        val joinButton = findViewById<Button>(R.id.joinButton)
        val pickFileButton = findViewById<Button>(R.id.pickFileButton)
        val discoverButton = findViewById<Button>(R.id.discoverButton)
        val libraryButton = findViewById<Button>(R.id.libraryButton)
        val cancelButton = findViewById<Button>(R.id.cancelButton)
        val softApButton = findViewById<Button>(R.id.softApButton)
        val showQrButton = findViewById<Button>(R.id.showQrButton)
        val applyQrButton = findViewById<Button>(R.id.applyQrButton)
        val qrPayloadInput = findViewById<EditText>(R.id.qrPayloadInput)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val payloadInput = findViewById<EditText>(R.id.payloadInput)

        nsd = NsdNearby(this)
        nsd.onStatus = { message -> status.text = message }
        nsd.onPeersChanged = { peers ->
            lastPeers = peers
            val labels = if (peers.isEmpty()) listOf("(none yet)")
            else peers.map { "${it.deviceName}  ${it.host}:${it.port}" }
            peersList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }
        peersList.setOnItemClickListener { _, _, position, _ ->
            val peer = lastPeers.getOrNull(position) ?: return@setOnItemClickListener
            hostInput.setText(peer.host)
            portInput.setText(peer.port.toString())
            status.text = "Selected: ${peer.deviceName} (${peer.host}:${peer.port})"
        }

        cancelButton.isEnabled = false
        cancelButton.setOnClickListener {
            activeControl.get()?.cancel()
            status.text = "Cancel requested…"
        }

        softApButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                requestNearbyWifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                return@setOnClickListener
            }
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton), cancelButton, status) {
                softAp?.stop()
                softAp = SoftApLink(this@MainActivity)
                val creds = softAp!!.startBlocking()
                val uri = QrBootstrap.encodeUri(
                    QrBootstrap.Payload(
                        host = creds.suggestedHostIp,
                        port = LanLink.DEFAULT_PORT,
                        ssid = creds.ssid,
                        password = creds.password,
                        deviceName = nameInput.text?.toString(),
                    ),
                )
                mainHandler.post {
                    hostInput.setText(creds.suggestedHostIp)
                    portInput.setText(LanLink.DEFAULT_PORT.toString())
                    qrPayloadInput.setText(uri)
                }
                "SoftAP ON\nSSID=${creds.ssid}\nPASS=${creds.password}\nHost address: ${creds.suggestedHostIp}\nNext: tap Host, then connect the other phone to this Wi‑Fi and use the QR details."
            }
        }

        showQrButton.setOnClickListener {
            val host = hostInput.text?.toString()?.ifBlank { null }
                ?: localIpv4List().lineSequence().map { it.substringAfter(": ").trim() }.firstOrNull()
                ?: "0.0.0.0"
            val uri = qrPayloadInput.text?.toString()?.takeIf { it.startsWith("warpair://") }
                ?: QrBootstrap.encodeUri(
                    QrBootstrap.Payload(
                        host = host,
                        port = portInput.text?.toString()?.toIntOrNull() ?: LanLink.DEFAULT_PORT,
                        deviceName = nameInput.text?.toString(),
                    ),
                )
            qrPayloadInput.setText(uri)
            val bmp = QrBootstrap.toBitmap(uri, 640)
            showQrDialog(bmp, uri)
        }

        applyQrButton.setOnClickListener {
            try {
                val p = QrBootstrap.parseUri(qrPayloadInput.text?.toString().orEmpty())
                hostInput.setText(p.host)
                portInput.setText(p.port.toString())
                status.text = buildString {
                    appendLine("QR applied: ${p.host}:${p.port}")
                    p.ssid?.let { appendLine("SSID=$it (connect to this Wi‑Fi first)") }
                    p.deviceName?.let { appendLine("peer name hint=$it") }
                }
            } catch (t: Throwable) {
                status.text = "Bad QR payload: ${t.message}"
            }
        }

        ensureStorageAccess()
        refreshLibraryStatus()
        handleWarpairIntent(intent)

        libraryButton.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        pickFileButton.setOnClickListener { openDocuments.launch(arrayOf("*/*")) }
        discoverButton.setOnClickListener {
            nsd.startDiscovery()
            status.text = "Discovering WarPair peers…"
        }

        smokeButton.setOnClickListener {
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton), cancelButton, status) {
                val report = NearbyCore.smokeTest()
                if (report.startsWith("OK")) "Smoke passed\n\n$report" else "Smoke failed\n\n$report"
            }
        }

        hostButton.setOnClickListener {
            if (!ReceivedFiles.hasWritableLibrary()) {
                status.text = "Grant storage access for /WarPair library"
                ensureStorageAccess()
                return@setOnClickListener
            }
            val name = nameInput.text?.toString()?.ifBlank { "Host" } ?: "Host"
            val control = LanLink.TransferControl()
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton), cancelButton, status, control) {
                try {
                    LanLink.hostOnce(
                        deviceName = name,
                        control = control,
                        onProgress = progressTo(status),
                        onListening = { boundPort ->
                            mainHandler.post {
                                nsd.advertise(name, boundPort)
                                status.text = "Waiting for a peer on port $boundPort\\n\\nThis phone's addresses:\\n${localIpv4List()}\\n\\nBoth phones must use the same Wi‑Fi network."
                            }
                        },
                        onPinConfirm = LanLink.PinConfirm { peer, pin -> askPinConfirm(peer, pin) },
                        onOffer = LanLink.OfferDecision { offer -> askAcceptOffer(offer) },
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
                status.text = "Enter host IP, tap a peer, or Apply QR."
                return@setOnClickListener
            }
            val peerPort = portInput.text?.toString()?.toIntOrNull() ?: LanLink.DEFAULT_PORT
            if (peerPort !in 1..65535) {
                status.text = "Enter a valid TCP port (1–65535)."
                return@setOnClickListener
            }
            val files = if (pickedFiles.isNotEmpty()) {
                pickedFiles.map { p ->
                    LanLink.OutgoingFile(p.displayName, p.size) {
                        ReceivedFiles.openPickedStream(this@MainActivity, p.uri)
                    }
                }
            } else {
                val bytes = (payloadInput.text?.toString() ?: "").toByteArray(Charsets.UTF_8)
                listOf(LanLink.OutgoingFile("note.txt", bytes.size.toLong()) { ByteArrayInputStream(bytes) })
            }
            val control = LanLink.TransferControl()
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton), cancelButton, status, control) {
                LanLink.connectOnce(
                    host = host,
                    port = peerPort,
                    deviceName = name,
                    files = files,
                    control = control,
                    onProgress = progressTo(status),
                    onPinConfirm = LanLink.PinConfirm { peer, pin -> askPinConfirm(peer, pin) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWarpairIntent(intent)
    }

    private fun handleWarpairIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!data.startsWith("warpair://", ignoreCase = true)) return
        try {
            val p = QrBootstrap.parseUri(data)
            findViewById<EditText>(R.id.qrPayloadInput).setText(data)
            findViewById<EditText>(R.id.hostInput).setText(p.host)
            findViewById<EditText>(R.id.portInput).setText(p.port.toString())
            findViewById<TextView>(R.id.statusText).text = "Opened WarPair link → ${p.host}:${p.port}"
        } catch (t: Throwable) {
            findViewById<TextView>(R.id.statusText).text = "Bad link: ${t.message}"
        }
    }

    private fun showQrDialog(bmp: Bitmap, uri: String) {
        val image = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle("WarPair QR")
            .setMessage(uri)
            .setView(image)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun askPinConfirm(peerName: String, pin: String): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        mainHandler.post {
            if (isFinishing) {
                latch.countDown()
                return@post
            }
            AlertDialog.Builder(this)
                .setTitle("Confirm PIN")
                .setMessage("Connected to: $peerName\n\nCode:\n\n    $pin\n\nMatch codes on both phones.")
                .setCancelable(false)
                .setPositiveButton("Codes match") { _, _ -> ok.set(true); latch.countDown() }
                .setNegativeButton("Abort") { _, _ -> ok.set(false); latch.countDown() }
                .show()
        }
        return latch.await(120, TimeUnit.SECONDS) && ok.get()
    }

    private fun askAcceptOffer(offer: LanLink.OfferRequest): LanLink.OfferResult {
        val latch = CountDownLatch(1)
        val choice = AtomicReference(LanLink.OfferResult(LanLink.OfferAction.REJECT))
        val partial = offer.partial
        mainHandler.post {
            if (isFinishing) {
                latch.countDown()
                return@post
            }
            val b = AlertDialog.Builder(this)
                .setTitle("Incoming (#${offer.index})")
                .setMessage(
                    "${offer.fileName}\n${offer.size} bytes\n→ WarPair/${offer.category.folderName}/" +
                        if (partial != null) "\nPartial: ${partial.bytesReceived} bytes" else "",
                )
                .setCancelable(false)
                .setNegativeButton("Reject") { _, _ ->
                    choice.set(LanLink.OfferResult(LanLink.OfferAction.REJECT)); latch.countDown()
                }
                .setPositiveButton("Accept") { _, _ ->
                    choice.set(LanLink.OfferResult(LanLink.OfferAction.ACCEPT_NEW)); latch.countDown()
                }
            if (partial != null && partial.bytesReceived > 0) {
                b.setNeutralButton("Resume") { _, _ ->
                    choice.set(LanLink.OfferResult(LanLink.OfferAction.RESUME, partial))
                    latch.countDown()
                }
            }
            b.show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return choice.get()
    }

    override fun onDestroy() {
        activeControl.get()?.cancel()
        softAp?.stop()
        nsd.shutdown()
        super.onDestroy()
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    requestAllFilesAccess.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        },
                    )
                } catch (_: Exception) {
                    requestAllFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (need.isNotEmpty()) requestLegacyStorage.launch(need.toTypedArray())
        }
    }

    private fun refreshLibraryStatus() {
        val status = findViewById<TextView>(R.id.statusText) ?: return
        val writable = ReceivedFiles.hasWritableLibrary()
        val root = try {
            ReceivedFiles.rootDir().absolutePath
        } catch (_: Exception) {
            "/storage/emulated/0/WarPair"
        }
        status.text = buildString {
            appendLine("WarPair library:")
            appendLine(root)
            appendLine(if (writable) "Storage: writable ✓" else "Storage: grant All files access")
            appendLine()
            appendLine("IPs:")
            append(localIpv4List())
        }
    }

    private fun progressTo(status: TextView) = LanLink.Progress { done, total, phase ->
        val line = if (total > 0) {
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            "$phase  $done/$total ($pct%)"
        } else "$phase  $done bytes"
        mainHandler.post { status.text = line }
    }

    private fun runBg(
        buttons: List<Button>,
        cancelButton: Button,
        status: TextView,
        control: LanLink.TransferControl? = null,
        block: () -> String,
    ) {
        buttons.forEach { it.isEnabled = false }
        cancelButton.isEnabled = control != null
        activeControl.set(control)
        status.text = "Working…"
        Thread {
            val result = try {
                block()
            } catch (t: Throwable) {
                "Error: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(1200)}"
            }
            runOnUiThread {
                status.text = result
                buttons.forEach { it.isEnabled = true }
                cancelButton.isEnabled = false
                activeControl.set(null)
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

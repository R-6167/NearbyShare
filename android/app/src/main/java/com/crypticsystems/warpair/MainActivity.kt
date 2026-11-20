package com.crypticsystems.warpair

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    private var wifiDirect: WifiDirectLink? = null
    private var bluetooth: BluetoothLink? = null
    private var bluetoothPeers: List<BluetoothLink.Peer> = emptyList()
    private var selectedBluetoothPeer: BluetoothLink.Peer? = null
    private var wifiDirectPeers: List<WifiDirectLink.Peer> = emptyList()
    private var selectedWifiDirectPeer: WifiDirectLink.Peer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeControl = AtomicReference<LanLink.TransferControl?>(null)
    private val activeTransportCancel = AtomicReference<(() -> Unit)?>(null)

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
            "Nearby Wi-Fi permission granted. Retry your Wi-Fi connection action."
        } else {
            "Nearby Wi-Fi permission is required for Wi-Fi Direct and hotspot operations."
        }
    }

    private val requestWifiDirectLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        findViewById<TextView>(R.id.statusText)?.text = if (granted) "Location permission granted. Tap Find Wi-Fi Direct devices again." else "Wi-Fi Direct discovery requires Location permission on this Android version."
    }

    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        findViewById<TextView>(R.id.statusText)?.text = if (grants.values.all { it }) {
            "Bluetooth permissions granted. Retry the Bluetooth action."
        } else {
            "Bluetooth Nearby devices permission is required. Allow it and retry."
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned.isNullOrBlank()) {
            findViewById<TextView>(R.id.statusText)?.text = "QR scan cancelled."
        } else {
            applyScannedQr(scanned)
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
        val wifiDirectDiscoverButton = findViewById<Button>(R.id.wifiDirectDiscoverButton)
        val wifiDirectHostButton = findViewById<Button>(R.id.wifiDirectHostButton)
        val wifiDirectJoinButton = findViewById<Button>(R.id.wifiDirectJoinButton)
        val wifiDirectPeersList = findViewById<ListView>(R.id.wifiDirectPeersList)
        val bluetoothDiscoverButton = findViewById<Button>(R.id.bluetoothDiscoverButton)
        val bluetoothHostButton = findViewById<Button>(R.id.bluetoothHostButton)
        val bluetoothJoinButton = findViewById<Button>(R.id.bluetoothJoinButton)
        val bluetoothPeersList = findViewById<ListView>(R.id.bluetoothPeersList)
        val showQrButton = findViewById<Button>(R.id.showQrButton)
        val applyQrButton = findViewById<Button>(R.id.applyQrButton)
        val scanQrButton = findViewById<Button>(R.id.scanQrButton)
        val qrPayloadInput = findViewById<EditText>(R.id.qrPayloadInput)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val payloadInput = findViewById<EditText>(R.id.payloadInput)

        bluetooth = BluetoothLink(this)
        bluetoothPeersList.setOnItemClickListener { _, _, position, _ ->
            selectedBluetoothPeer = bluetoothPeers.getOrNull(position)
            selectedBluetoothPeer?.let { peer ->
                status.text = "Selected Bluetooth device: ${peer.name} (${if (peer.bonded) "paired" else "not paired yet"})."
            }
        }

        wifiDirect = WifiDirectLink(this).also { adapter ->
            adapter.setListener(object : WifiDirectLink.Listener {
                override fun onPeersChanged(peers: List<WifiDirectLink.Peer>) {
                    wifiDirectPeers = peers
                    wifiDirectPeersList.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                        if (peers.isEmpty()) listOf("(No Wi-Fi Direct peers found yet)")
                        else peers.map { "${it.name.ifBlank { "WarPair device" }}  ${it.address}" })
                    if (selectedWifiDirectPeer != null && peers.none { it.address == selectedWifiDirectPeer?.address }) selectedWifiDirectPeer = null
                }
                override fun onStatus(message: String) { mainHandler.post { status.text = message } }
            })
        }
        wifiDirectPeersList.setOnItemClickListener { _, _, position, _ ->
            selectedWifiDirectPeer = wifiDirectPeers.getOrNull(position)
            selectedWifiDirectPeer?.let { status.text = "Selected Wi-Fi Direct peer: ${it.name} (${it.address})" }
        }

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
            activeTransportCancel.get()?.invoke()
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

        scanQrButton.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan the QR code displayed on the other WarPair device")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            scanQrLauncher.launch(options)
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
            status.text = "Discovering WarPair peers on this Wi-Fi network…"
        }

        bluetoothDiscoverButton.setOnClickListener {
            if (!ensureBluetoothPermissions()) return@setOnClickListener
            startBluetoothDiscovery()
        }

        bluetoothHostButton.setOnClickListener {
            if (!ensureBluetoothPermissions()) return@setOnClickListener
            if (!ReceivedFiles.hasWritableLibrary()) {
                status.text = "Grant storage access before receiving files."
                ensureStorageAccess()
                return@setOnClickListener
            }
            val adapter = getBluetoothLink()
            if (!adapter.isSupported()) { status.text = "Bluetooth is not supported on this device."; return@setOnClickListener }
            if (!adapter.isEnabled()) { status.text = "Turn on Bluetooth, then retry Bluetooth receive."; return@setOnClickListener }
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
            val name = nameInput.text?.toString()?.ifBlank { "WarPair Bluetooth host" } ?: "WarPair Bluetooth host"
            val control = LanLink.TransferControl()
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton,
                    wifiDirectDiscoverButton, wifiDirectHostButton, wifiDirectJoinButton,
                    bluetoothDiscoverButton, bluetoothHostButton, bluetoothJoinButton),
                cancelButton, status, control, cancelTransport = { adapter.close() },
            ) {
                try {
                    mainHandler.post { status.text = "Waiting for a nearby Bluetooth device…" }
                    val connection = adapter.acceptPeer()
                    LanLink.hostConnected(
                        connection = connection,
                        deviceName = name,
                        control = control,
                        onProgress = progressTo(status),
                        onPinConfirm = LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                        onOffer = LanLink.OfferDecision { offer -> askAcceptOffer(offer) },
                    )
                } finally {
                    adapter.close()
                    if (bluetooth === adapter) bluetooth = null
                }
            }
        }

        bluetoothJoinButton.setOnClickListener {
            if (!ensureBluetoothPermissions()) return@setOnClickListener
            val peer = selectedBluetoothPeer
            if (peer == null) { status.text = "Find Bluetooth devices, then select the other phone."; return@setOnClickListener }
            val adapter = getBluetoothLink()
            if (!adapter.isEnabled()) { status.text = "Turn on Bluetooth, then retry."; return@setOnClickListener }
            val name = nameInput.text?.toString()?.ifBlank { "WarPair Bluetooth sender" } ?: "WarPair Bluetooth sender"
            val files = buildOutgoingFiles(payloadInput.text?.toString().orEmpty())
            val control = LanLink.TransferControl()
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton,
                    wifiDirectDiscoverButton, wifiDirectHostButton, wifiDirectJoinButton,
                    bluetoothDiscoverButton, bluetoothHostButton, bluetoothJoinButton),
                cancelButton, status, control, cancelTransport = { adapter.close() },
            ) {
                try {
                    val connection = adapter.connect(peer.address)
                    LanLink.sendConnected(
                        connection = connection,
                        deviceName = name,
                        files = files,
                        control = control,
                        onProgress = progressTo(status),
                        onPinConfirm = LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                    )
                } finally {
                    adapter.close()
                    if (bluetooth === adapter) bluetooth = null
                }
            }
        }

        wifiDirectDiscoverButton.setOnClickListener {
            if (!ensureWifiDirectPermission()) return@setOnClickListener
            wifiDirect?.discoverPeers()
            status.text = "Searching for nearby Wi-Fi Direct devices… Keep Wi-Fi enabled on both phones."
        }

        wifiDirectHostButton.setOnClickListener {
            if (!ensureWifiDirectPermission()) return@setOnClickListener
            if (!ReceivedFiles.hasWritableLibrary()) { status.text = "Grant storage access before receiving files."; ensureStorageAccess(); return@setOnClickListener }
            val adapter = wifiDirect ?: run { status.text = "Wi-Fi Direct is unavailable on this device."; return@setOnClickListener }
            val name = nameInput.text?.toString()?.ifBlank { "WarPair host" } ?: "WarPair host"
            val control = LanLink.TransferControl()
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton, wifiDirectDiscoverButton, wifiDirectHostButton, wifiDirectJoinButton), cancelButton, status, control) {
                WifiDirectTransferController.host(adapter, name, control, progressTo(status),
                    LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                    LanLink.OfferDecision { offer -> askAcceptOffer(offer) },
                    statusCallback = { message -> mainHandler.post { status.text = message } })
            }
        }

        wifiDirectJoinButton.setOnClickListener {
            if (!ensureWifiDirectPermission()) return@setOnClickListener
            val peer = selectedWifiDirectPeer
            if (peer == null) { status.text = "Find Wi-Fi Direct devices, then select the other phone."; return@setOnClickListener }
            val adapter = wifiDirect ?: run { status.text = "Wi-Fi Direct is unavailable on this device."; return@setOnClickListener }
            val name = nameInput.text?.toString()?.ifBlank { "WarPair sender" } ?: "WarPair sender"
            val files = buildOutgoingFiles(payloadInput.text?.toString().orEmpty())
            val control = LanLink.TransferControl()
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton, wifiDirectDiscoverButton, wifiDirectHostButton, wifiDirectJoinButton), cancelButton, status, control) {
                WifiDirectTransferController.join(adapter, peer.address, name, files, control, progressTo(status),
                    LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                    statusCallback = { message -> mainHandler.post { status.text = message } })
            }
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
            val listenPort = portInput.text?.toString()?.toIntOrNull() ?: LanLink.DEFAULT_PORT
            if (listenPort !in 1..65535) {
                status.text = "Enter a valid listening port (1–65535)."
                return@setOnClickListener
            }
            val control = LanLink.TransferControl()
            val activeReceiver = AtomicReference<MultiTransportReceiver?>(null)
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton),
                cancelButton, status, control,
                cancelTransport = { activeReceiver.get()?.close() },
            ) {
                var bluetoothAdapter: BluetoothLink? = null
                var receiver: MultiTransportReceiver? = null
                var finalResult: String? = null
                var peerAttempt = 0
                try {
                    while (finalResult == null) {
                        control.check()
                        peerAttempt++
                        val acceptors = mutableListOf<ReceiverAcceptor>()
                        acceptors += TcpReceiverAcceptor(listenPort) { boundPort ->
                            mainHandler.post {
                                nsd.advertise(name, boundPort)
                                status.text = "Listening on port $boundPort over Wi-Fi / LAN / SoftAP.\\n" +
                                    "This phone's addresses:\\n${localIpv4List()}\\n" +
                                    "Bluetooth will also accept a peer when its permission is available."
                            }
                        }
                        bluetoothAdapter = try {
                            getBluetoothLink().takeIf { it.isSupported() && it.isEnabled() }
                        } catch (_: Throwable) {
                            null
                        }
                        bluetoothAdapter?.let { adapter ->
                            adapter.stopDiscovery()
                            acceptors += BluetoothReceiverAcceptor(adapter)
                        }
                        val active = MultiTransportReceiver(acceptors)
                        receiver = active
                        activeReceiver.set(active)
                        mainHandler.post {
                            status.text = if (bluetoothAdapter != null) {
                                "Waiting for a peer over Wi-Fi / LAN / SoftAP or Bluetooth…"
                            } else {
                                "Waiting for a peer over Wi-Fi / LAN / SoftAP… (Bluetooth permission unavailable)"
                            }
                        }

                        // Listener setup/accept failures are fatal for this host session. Once a
                        // socket has been selected, however, a bad handshake or dropped transfer
                        // must not leave the receiver permanently closed.
                        val connection = active.accept(control)
                        try {
                            val result = LanLink.hostConnected(
                                connection = connection,
                                deviceName = name,
                                control = control,
                                onProgress = progressTo(status),
                                onPinConfirm = LanLink.PinConfirm { peer, pin -> askPinConfirm(peer, pin) },
                                onOffer = LanLink.OfferDecision { offer -> askAcceptOffer(offer) },
                            )
                            if (result == "PIN not confirmed (host)" || result == "Peer cancelled") {
                                mainHandler.post {
                                    status.text = "Peer session ended before transfer. Reopening receiver on all available transports…"
                                }
                            } else {
                                finalResult = result
                            }
                        } catch (cancelled: java.util.concurrent.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (control.isCancelled()) throw LanLink.TransferCancelledException()
                            mainHandler.post {
                                status.text = "Peer disconnected or session failed (${failure.message ?: failure.javaClass.simpleName}). " +
                                    "Reopening receiver; verified files and durable partial checkpoints are retained…"
                            }
                        } finally {
                            receiver?.close()
                            receiver = null
                            activeReceiver.set(null)
                            if (bluetooth === bluetoothAdapter) bluetooth = null
                            bluetoothAdapter = null
                            mainHandler.post { nsd.stopAdvertising() }
                        }
                        if (finalResult == null) Thread.sleep(750)
                    }
                } finally {
                    receiver?.close()
                    activeReceiver.set(null)
                    if (bluetooth === bluetoothAdapter) bluetooth = null
                    mainHandler.post { nsd.stopAdvertising() }
                }
                finalResult ?: "Receiver stopped."
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

    private fun applyScannedQr(raw: String) {
        try {
            val payload = QrBootstrap.parseUri(raw)
            findViewById<EditText>(R.id.qrPayloadInput).setText(raw)
            findViewById<EditText>(R.id.hostInput).setText(payload.host)
            findViewById<EditText>(R.id.portInput).setText(payload.port.toString())
            findViewById<TextView>(R.id.statusText).text = buildString {
                appendLine("WarPair QR scanned successfully.")
                appendLine("Peer: ${payload.deviceName ?: "WarPair device"}")
                appendLine("Address: ${payload.host}:${payload.port}")
                payload.ssid?.let { appendLine("Wi‑Fi: $it — join this network before connecting.") }
                append("Ready. Choose files if needed, then tap Connect / Send.")
            }
        } catch (t: Throwable) {
            findViewById<TextView>(R.id.statusText).text =
                "This QR code is not a valid WarPair connection code: ${t.message}"
        }
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
        wifiDirect?.close()
        wifiDirect = null
        bluetooth?.close()
        bluetooth = null
        softAp?.stop()
        nsd.shutdown()
        super.onDestroy()
    }

    private fun getBluetoothLink(): BluetoothLink {
        val current = bluetooth
        if (current != null) return current
        return BluetoothLink(this).also { bluetooth = it }
    }

    private fun ensureBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val required = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
            val missing = required.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestBluetoothPermissions.launch(missing.toTypedArray())
                return false
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return false
        }
        return true
    }

    private fun startBluetoothDiscovery() {
        val status = findViewById<TextView>(R.id.statusText)
        val list = findViewById<ListView>(R.id.bluetoothPeersList)
        val adapter = try { getBluetoothLink() } catch (error: Throwable) {
            status.text = "Bluetooth unavailable: ${error.message}"
            return
        }
        if (!adapter.isSupported()) {
            status.text = "This device does not support Bluetooth."
            return
        }
        if (!adapter.isEnabled()) {
            status.text = "Bluetooth is off. Turn it on in Android quick settings, then retry."
            return
        }
        status.text = "Searching for nearby Bluetooth devices…"
        adapter.startDiscovery(object : BluetoothLink.DiscoveryListener {
            override fun onPeersChanged(peers: List<BluetoothLink.Peer>) {
                mainHandler.post {
                    bluetoothPeers = peers
                    list.adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        if (peers.isEmpty()) listOf("(No Bluetooth devices found yet)")
                        else peers.map { "${it.name}  • ${if (it.bonded) "Paired" else "Pair on connect"}" },
                    )
                    if (selectedBluetoothPeer != null && peers.none { it.address == selectedBluetoothPeer?.address }) {
                        selectedBluetoothPeer = null
                    }
                }
            }
            override fun onFinished() {
                mainHandler.post { status.text = "Bluetooth scan finished. Select a device; Android may ask you to pair when connecting." }
            }
            override fun onError(message: String) {
                mainHandler.post { status.text = message }
            }
        })
    }

    private fun ensureWifiDirectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                requestNearbyWifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES); return false
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestWifiDirectLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION); return false
        }
        return true
    }

    private fun buildOutgoingFiles(note: String): List<LanLink.OutgoingFile> {
        if (pickedFiles.isNotEmpty()) return pickedFiles.map { picked ->
            LanLink.OutgoingFile(picked.displayName, picked.size) { ReceivedFiles.openPickedStream(this@MainActivity, picked.uri) }
        }
        val bytes = note.toByteArray(Charsets.UTF_8)
        return listOf(LanLink.OutgoingFile("note.txt", bytes.size.toLong()) { ByteArrayInputStream(bytes) })
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
        cancelTransport: (() -> Unit)? = null,
        block: () -> String,
    ) {
        buttons.forEach { it.isEnabled = false }
        cancelButton.isEnabled = control != null
        activeControl.set(control)
        activeTransportCancel.set(cancelTransport)
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
                activeTransportCancel.set(null)
            }
        }.start()
    }

    private fun localIpv4List(): String {
        val addresses = mutableListOf<Pair<String, String>>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val address = addrs.nextElement()
                    val host = address.hostAddress
                    if (!address.isLoopbackAddress && host?.contains(':') == false) {
                        addresses += ni.name to host
                    }
                }
            }
        } catch (_: Exception) {
        }
        fun priority(name: String): Int {
            val n = name.lowercase()
            return when {
                n.contains("softap") || n.contains("ap") || n.contains("wlan") || n.contains("swlan") -> 0
                n.contains("eth") -> 1
                else -> 2
            }
        }
        val ips = addresses.sortedBy { priority(it.first) }
            .map { "${it.first}: ${it.second}" }
        return if (ips.isEmpty()) "(none)" else ips.joinToString("\n")
    }
}

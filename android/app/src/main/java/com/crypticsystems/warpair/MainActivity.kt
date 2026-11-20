package com.crypticsystems.warpair

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
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
    private var scanAfterFilePick = false
    private var pendingScannedQr: String? = null
    @Volatile private var activeInvitationUri: String? = null
    @Volatile private var pendingQrPeerName: String? = null
    @Volatile private var pendingQrAutoJoin = false
    @Volatile private var pendingQrHasBluetooth = false
    @Volatile private var pendingQrInvitation: QrBootstrap.Invitation? = null
    private var lastPeers: List<NsdNearby.Peer> = emptyList()
    private lateinit var nsd: NsdNearby
    private var softAp: SoftApLink? = null
    @Volatile private var activeSoftApCredentials: SoftApLink.Credentials? = null
    private var wifiDirect: WifiDirectLink? = null
    private var bluetooth: BluetoothLink? = null
    private var bluetoothPeers: List<BluetoothLink.Peer> = emptyList()
    @Volatile private var selectedBluetoothPeer: BluetoothLink.Peer? = null
    private var wifiDirectPeers: List<WifiDirectLink.Peer> = emptyList()
    @Volatile private var selectedWifiDirectPeer: WifiDirectLink.Peer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeControl = AtomicReference<LanLink.TransferControl?>(null)
    private val activeTransportCancel = AtomicReference<(() -> Unit)?>(null)

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            scanAfterFilePick = false
            return@registerForActivityResult
        }
        val loaded = mutableListOf<PickedFile>()
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            try {
                loaded += ReceivedFiles.readPickedMeta(this, uri)
            } catch (t: Throwable) {
                scanAfterFilePick = false
                findViewById<TextView>(R.id.pickedFileText).text = "Failed to read: ${t.message}"
                return@registerForActivityResult
            }
        }
        pickedFiles = loaded
        findViewById<TextView>(R.id.pickedFileText).text =
            loaded.joinToString("\n", prefix = "${loaded.size} file(s):\n") { "• ${it.displayName}" }
        val pendingQr = pendingScannedQr
        if (pendingQr != null) {
            pendingScannedQr = null
            scanAfterFilePick = false
            applyScannedQr(pendingQr)
        } else if (scanAfterFilePick) {
            scanAfterFilePick = false
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan the receiver's WarPair QR")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            scanQrLauncher.launch(options)
        }
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

    private val wifiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (pendingQrAutoJoin && pickedFiles.isNotEmpty()) {
            pendingQrAutoJoin = false
            findViewById<Button>(R.id.joinButton).performClick()
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
        val quickSendButton = findViewById<Button>(R.id.quickSendButton)
        val quickReceiveButton = findViewById<Button>(R.id.quickReceiveButton)
        val quickScanButton = findViewById<Button>(R.id.quickScanButton)
        val quickFilesButton = findViewById<Button>(R.id.quickFilesButton)
        val quickToolsButton = findViewById<Button>(R.id.quickToolsButton)
        val advancedPairingCard = findViewById<View>(R.id.advancedPairingCard)
        val advancedTransferCard = findViewById<View>(R.id.advancedTransferCard)
        val advancedPairingHeading = findViewById<View>(R.id.advancedPairingHeading)
        val advancedTransferHeading = findViewById<View>(R.id.advancedTransferHeading)

        // Primary actions stay simple; transport-specific controls remain available
        // below for diagnostics and compatibility while the unified invitation flow is built.
        quickSendButton.setOnClickListener {
            scanAfterFilePick = true
            openDocuments.launch(arrayOf("*/*"))
        }
        quickReceiveButton.setOnClickListener { hostButton.performClick() }
        quickScanButton.setOnClickListener { scanQrButton.performClick() }
        quickFilesButton.setOnClickListener { libraryButton.performClick() }
        quickToolsButton.setOnClickListener {
            val show = advancedPairingCard.visibility != View.VISIBLE
            advancedPairingCard.visibility = if (show) View.VISIBLE else View.GONE
            advancedTransferCard.visibility = if (show) View.VISIBLE else View.GONE
            advancedPairingHeading.visibility = if (show) View.VISIBLE else View.GONE
            advancedTransferHeading.visibility = if (show) View.VISIBLE else View.GONE
            smokeButton.visibility = if (show) View.VISIBLE else View.GONE
            quickToolsButton.text = if (show) "Close tools" else "More tools"
        }

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
                    val expected = pendingQrPeerName
                    val match = if (expected == null) null else peers.firstOrNull { it.name.equals(expected, ignoreCase = true) }
                    if (match != null && pendingQrAutoJoin) {
                        pendingQrAutoJoin = false
                        pendingQrPeerName = null
                        selectedWifiDirectPeer = match
                        val waitForBluetooth = pendingQrHasBluetooth && selectedBluetoothPeer == null
                        pendingQrHasBluetooth = false
                        mainHandler.postDelayed({
                            status.text = "Found ${match.name} from the QR invitation. Connecting…"
                            findViewById<Button>(R.id.wifiDirectJoinButton).performClick()
                        }, if (waitForBluetooth) 5_000L else 0L)
                    }
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
                activeSoftApCredentials = creds
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
            val uri = activeInvitationUri
                ?: qrPayloadInput.text?.toString()?.takeIf { it.startsWith("warpair://") }
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
            val wifiPeer = selectedWifiDirectPeer
            if (wifiPeer != null && !ensureWifiDirectPermission()) return@setOnClickListener
            val wifiAdapter = if (wifiPeer != null) wifiDirect else null
            val name = nameInput.text?.toString()?.ifBlank { "WarPair Bluetooth sender" } ?: "WarPair Bluetooth sender"
            val files = buildOutgoingFiles(payloadInput.text?.toString().orEmpty())
            val control = LanLink.TransferControl()
            val routes = if (wifiPeer != null && wifiAdapter != null) {
                listOf(TransferTransport.BLUETOOTH, TransferTransport.WIFI_DIRECT)
            } else listOf(TransferTransport.BLUETOOTH)
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton,
                    wifiDirectDiscoverButton, wifiDirectHostButton, wifiDirectJoinButton,
                    bluetoothDiscoverButton, bluetoothHostButton, bluetoothJoinButton),
                cancelButton, status, control, cancelTransport = { adapter.close() },
            ) {
                try {
                    val attempts = mutableMapOf<TransferTransport, () -> String>()
                    attempts[TransferTransport.BLUETOOTH] = {
                        val connection = adapter.connect(peer.address)
                        LanLink.sendConnected(
                            connection, name, files, control, progressTo(status),
                            LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                            onFileCompleted = { completed ->
                                mainHandler.post { status.text = "Verified on receiver: ${completed.fileName}" }
                            },
                        )
                    }
                    if (wifiPeer != null && wifiAdapter != null) {
                        attempts[TransferTransport.WIFI_DIRECT] = {
                            mainHandler.post { status.text = "Bluetooth failed. Switching to Wi-Fi Direct without changing transfer identity…" }
                            WifiDirectTransferController.join(
                                wifiAdapter, wifiPeer.address, name, files, control, progressTo(status),
                                LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                                statusCallback = { message -> mainHandler.post { status.text = message } },
                                onFileCompleted = { completed ->
                                    mainHandler.post { status.text = "Verified on receiver: ${completed.fileName}" }
                                },
                            )
                        }
                    }
                    TransferRouteRunner(routes).execute(
                        attempts, control,
                        onRouteChanged = { failed, next, reason ->
                            mainHandler.post {
                                status.text = if (next != null) {
                                    "$failed failed ($reason). Trying $next with the same batch and file IDs…"
                                } else "$failed failed ($reason). No further route is available."
                            }
                        },
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
            val bluetoothPeer = selectedBluetoothPeer
            if (bluetoothPeer != null && !ensureBluetoothPermissions()) return@setOnClickListener
            val bluetoothAdapter = if (bluetoothPeer != null) getBluetoothLink() else null
            val name = nameInput.text?.toString()?.ifBlank { "WarPair sender" } ?: "WarPair sender"
            val files = buildOutgoingFiles(payloadInput.text?.toString().orEmpty())
            val control = LanLink.TransferControl()
            val routes = if (bluetoothPeer != null && bluetoothAdapter?.isEnabled() == true) {
                listOf(TransferTransport.WIFI_DIRECT, TransferTransport.BLUETOOTH)
            } else listOf(TransferTransport.WIFI_DIRECT)
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton,
                    wifiDirectDiscoverButton, wifiDirectHostButton, wifiDirectJoinButton,
                    bluetoothDiscoverButton, bluetoothHostButton, bluetoothJoinButton),
                cancelButton, status, control,
                cancelTransport = { bluetoothAdapter?.close() },
            ) {
                try {
                    val attempts = mutableMapOf<TransferTransport, () -> String>()
                    attempts[TransferTransport.WIFI_DIRECT] = {
                        WifiDirectTransferController.join(
                            adapter, peer.address, name, files, control, progressTo(status),
                            LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                            statusCallback = { message -> mainHandler.post { status.text = message } },
                            onFileCompleted = { completed ->
                                mainHandler.post { status.text = "Verified on receiver: ${completed.fileName}" }
                            },
                        )
                    }
                    if (bluetoothPeer != null && bluetoothAdapter?.isEnabled() == true) {
                        attempts[TransferTransport.BLUETOOTH] = {
                            mainHandler.post { status.text = "Wi-Fi Direct failed. Switching to Bluetooth without changing transfer identity…" }
                            val connection = bluetoothAdapter.connect(bluetoothPeer.address)
                            LanLink.sendConnected(
                                connection, name, files, control, progressTo(status),
                                LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                                onFileCompleted = { completed ->
                                    mainHandler.post { status.text = "Verified on receiver: ${completed.fileName}" }
                                },
                            )
                        }
                    }
                    TransferRouteRunner(routes).execute(
                        attempts, control,
                        onRouteChanged = { failed, next, reason ->
                            mainHandler.post {
                                status.text = if (next != null) {
                                    "$failed failed ($reason). Trying $next with the same batch and file IDs…"
                                } else "$failed failed ($reason). No further route is available."
                            }
                        },
                    )
                } finally {
                    bluetoothAdapter?.close()
                    if (bluetooth === bluetoothAdapter) bluetooth = null
                }
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
            try {
                val bt = getBluetoothLink()
                if (bt.isSupported() && bt.isEnabled()) {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                }
            } catch (_: Throwable) { /* LAN and Wi-Fi Direct remain available without Bluetooth. */ }
            val control = LanLink.TransferControl()
            val activeReceiver = AtomicReference<MultiTransportReceiver?>(null)
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton),
                cancelButton, status, control,
                cancelTransport = { activeReceiver.get()?.close() },
            ) {
                var bluetoothAdapter: BluetoothLink? = null
                var wifiDirectGroup: WifiDirectGroupSession? = null
                var receiver: MultiTransportReceiver? = null
                var finalResult: String? = null
                try {
                    while (finalResult == null) {
                        control.check()
                        val acceptors = mutableListOf<ReceiverAcceptor>()
                        acceptors += TcpReceiverAcceptor(listenPort) { boundPort ->
                            mainHandler.post {
                                nsd.advertise(name, boundPort)
                                status.text = "Listening on port $boundPort over Wi-Fi / LAN / SoftAP.\n" +
                                    "This phone's addresses:\n${localIpv4List()}\n" +
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
                        // A formed Wi-Fi Direct group routes into the existing wildcard TCP
                        // listener above. Do not bind a second socket on the same port.
                        wifiDirectGroup = if (wifiDirect != null && hasWifiDirectPermission()) {
                            try {
                                val groupSession = WifiDirectGroupSession(wifiDirect!!) { message ->
                                    mainHandler.post { status.text = message }
                                }
                                groupSession.start()
                                if (groupSession.awaitGroupCreation()) {
                                    groupSession
                                } else {
                                    groupSession.close()
                                    mainHandler.post {
                                        status.text = "Wi-Fi Direct group was not created. LAN and Bluetooth remain available."
                                    }
                                    null
                                }
                            } catch (groupFailure: Throwable) {
                                mainHandler.post {
                                    status.text = "Wi-Fi Direct group could not start (${groupFailure.message}); LAN and Bluetooth remain available."
                                }
                                null
                            }
                        } else null
                        val active = MultiTransportReceiver(acceptors)
                        receiver = active
                        activeReceiver.set(active)
                        val lanEndpoints = localIpv4Endpoints(listenPort)
                        val invitationRoutes = linkedSetOf<String>().apply {
                            if (lanEndpoints.isNotEmpty()) add("lan")
                            if (wifiDirectGroup != null) add("wifi_direct")
                            if (bluetoothAdapter?.localDeviceName()?.isNotBlank() == true) add("bluetooth")
                        }
                        val invitationUri = if (invitationRoutes.isNotEmpty()) {
                            QrBootstrap.encodeInvitation(
                                QrBootstrap.createInvitation(
                                    deviceName = name,
                                    routes = invitationRoutes,
                                    lanEndpoints = lanEndpoints,
                                    bluetoothServiceUuid = if ("bluetooth" in invitationRoutes) BluetoothLink.SERVICE_UUID.toString() else null,
                                    bluetoothDeviceName = bluetoothAdapter?.localDeviceName()?.takeIf { "bluetooth" in invitationRoutes },
                                    wifiSsid = activeSoftApCredentials?.takeIf { it.password.isNotBlank() }?.ssid,
                                    wifiPassword = activeSoftApCredentials?.password?.takeIf { it.isNotBlank() },
                                ),
                            )
                        } else null
                        activeInvitationUri = invitationUri
                        mainHandler.post {
                            val routeLabels = mutableListOf<String>()
                            if (lanEndpoints.isNotEmpty()) routeLabels += "LAN"
                            if (wifiDirectGroup != null) routeLabels += "Wi-Fi Direct"
                            if (bluetoothAdapter != null) routeLabels += "Bluetooth"
                            status.text = "Waiting for a peer over ${routeLabels.joinToString(" / ")}…"
                            if (invitationUri != null) {
                                qrPayloadInput.setText(invitationUri)
                                showQrDialog(QrBootstrap.toBitmap(invitationUri, 640), invitationUri)
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
                            activeInvitationUri = null
                            wifiDirectGroup?.close()
                            wifiDirectGroup = null
                            if (bluetooth === bluetoothAdapter) bluetooth = null
                            bluetoothAdapter = null
                            mainHandler.post { nsd.stopAdvertising() }
                        }
                        if (finalResult == null) Thread.sleep(750)
                    }
                } finally {
                    receiver?.close()
                    activeReceiver.set(null)
                    activeInvitationUri = null
                    wifiDirectGroup?.close()
                    wifiDirectGroup = null
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
            val invitation = pendingQrInvitation
            val useWifiDirect = invitation?.routes?.contains("wifi_direct") == true
            val useBluetooth = invitation?.routes?.contains("bluetooth") == true
            val btAdapter = if (useBluetooth && ensureBluetoothPermissions()) getBluetoothLink() else null
            val routes = mutableListOf(TransferTransport.WIFI_LAN)
            if (useWifiDirect) routes += TransferTransport.WIFI_DIRECT
            if (useBluetooth && btAdapter != null) routes += TransferTransport.BLUETOOTH
            runBg(
                listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton, softApButton),
                cancelButton, status, control, cancelTransport = { btAdapter?.close() },
            ) {
                try {
                    val attempts = mutableMapOf<TransferTransport, () -> String>()
                    attempts[TransferTransport.WIFI_LAN] = {
                        LanLink.connectOnce(host, peerPort, name, files, control, progressTo(status),
                            LanLink.PinConfirm { peer, pin -> askPinConfirm(peer, pin) })
                    }
                    if (useWifiDirect && wifiDirect != null) {
                        attempts[TransferTransport.WIFI_DIRECT] = {
                            mainHandler.post { status.text = "LAN failed. Resolving the Wi-Fi Direct peer…" }
                            val deadline = System.currentTimeMillis() + 10_000L
                            while (selectedWifiDirectPeer == null && System.currentTimeMillis() < deadline) {
                                control.check()
                                Thread.sleep(200)
                            }
                            val peer = selectedWifiDirectPeer ?: throw java.io.IOException("Wi-Fi Direct peer was not discovered.")
                            mainHandler.post { status.text = "Switching to Wi-Fi Direct with the same transfer IDs…" }
                            WifiDirectTransferController.join(wifiDirect!!, peer.address, name, files, control,
                                progressTo(status), LanLink.PinConfirm { peerName, pin -> askPinConfirm(peerName, pin) },
                                statusCallback = { message -> mainHandler.post { status.text = message } },
                                onFileCompleted = { completed -> mainHandler.post { status.text = "Verified on receiver: ${completed.fileName}" } })
                        }
                    }
                    if (useBluetooth && btAdapter != null) {
                        attempts[TransferTransport.BLUETOOTH] = {
                            mainHandler.post { status.text = "Resolving the receiver over Bluetooth…" }
                            val deadline = System.currentTimeMillis() + 10_000L
                            while (selectedBluetoothPeer == null && System.currentTimeMillis() < deadline) {
                                control.check()
                                Thread.sleep(200)
                            }
                            val peer = selectedBluetoothPeer ?: throw java.io.IOException("Bluetooth peer was not discovered.")
                            if (!btAdapter.isEnabled()) throw java.io.IOException("Bluetooth is disabled.")
                            mainHandler.post { status.text = "Switching to Bluetooth with the same transfer IDs…" }
                            val connection = btAdapter.connect(peer.address)
                            LanLink.sendConnected(connection, name, files, control, progressTo(status),
                                LanLink.PinConfirm { peer, pin -> askPinConfirm(peer, pin) },
                                onFileCompleted = { completed -> mainHandler.post { status.text = "Verified on receiver: ${completed.fileName}" } })
                        }
                    }
                    TransferRouteRunner(routes).execute(attempts, control) { failed, next, reason ->
                        mainHandler.post {
                            status.text = if (next != null) "$failed failed ($reason). Trying $next…" else
                                "$failed failed ($reason). No discovered fallback route is ready."
                        }
                    }
                } finally {
                    btAdapter?.close()
                    if (bluetooth === btAdapter) bluetooth = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWarpairIntent(intent)
    }

    private fun applyScannedQr(raw: String) {
        try {
            pendingScannedQr = if (pickedFiles.isEmpty()) raw else null
            findViewById<EditText>(R.id.qrPayloadInput).setText(raw)
            if (raw.trim().startsWith("warpair://invite?", ignoreCase = true)) {
                val invitation = QrBootstrap.parseInvitation(raw)
                pendingQrInvitation = invitation
                pendingQrPeerName = invitation.bluetoothDeviceName ?: invitation.deviceName
                pendingQrAutoJoin = pickedFiles.isNotEmpty() && invitation.lanEndpoints.isEmpty()
                pendingQrHasBluetooth = "bluetooth" in invitation.routes
                val lan = invitation.lanEndpoints.firstOrNull()
                if (lan != null) {
                    findViewById<EditText>(R.id.hostInput).setText(lan.host)
                    findViewById<EditText>(R.id.portInput).setText(lan.port.toString())
                    // Discover alternate peers while the LAN connection is attempted.
                    if ("wifi_direct" in invitation.routes && ensureWifiDirectPermission()) wifiDirect?.discoverPeers()
                    if ("bluetooth" in invitation.routes && ensureBluetoothPermissions()) {
                        startBluetoothDiscovery(invitation.bluetoothDeviceName ?: invitation.deviceName, autoConnect = false)
                    }
                    if (!invitation.wifiSsid.isNullOrBlank() && !invitation.wifiPassword.isNullOrBlank()) {
                        pendingQrAutoJoin = pickedFiles.isNotEmpty()
                        AlertDialog.Builder(this)
                            .setTitle("Join receiver Wi-Fi")
                            .setMessage("Network: ${invitation.wifiSsid}\\nPassword: ${invitation.wifiPassword}\\n\\nAndroid will ask you to join this temporary network. Return to WarPair when connected.")
                            .setPositiveButton("Open Wi-Fi settings") { _, _ ->
                                val settingsIntent = if (Build.VERSION.SDK_INT >= 29) {
                                    Intent(Settings.Panel.ACTION_WIFI)
                                } else Intent(Settings.ACTION_WIFI_SETTINGS)
                                wifiSettingsLauncher.launch(settingsIntent)
                            }
                            .setNegativeButton("Not now", null)
                            .show()
                        findViewById<TextView>(R.id.statusText).text = "Invitation from ${invitation.deviceName}. Join its temporary Wi-Fi network to continue."
                    } else {
                        findViewById<TextView>(R.id.statusText).text =
                            "Invitation from ${invitation.deviceName}. Connecting over the available local route…"
                        if (pickedFiles.isNotEmpty()) findViewById<Button>(R.id.joinButton).performClick()
                        else findViewById<TextView>(R.id.statusText).append("\\nChoose files, then tap Send.")
                    }
                    return
                }
                if ("wifi_direct" in invitation.routes && ensureWifiDirectPermission()) {
                    findViewById<TextView>(R.id.statusText).text =
                        "QR found ${invitation.deviceName}. Finding its Wi-Fi Direct route…"
                    if ("bluetooth" in invitation.routes && pickedFiles.isNotEmpty() && ensureBluetoothPermissions()) {
                        startBluetoothDiscovery(invitation.bluetoothDeviceName ?: invitation.deviceName, autoConnect = false)
                    }
                    wifiDirect?.discoverPeers()
                    if ("bluetooth" in invitation.routes && pickedFiles.isNotEmpty() && ensureBluetoothPermissions()) {
                        mainHandler.postDelayed({
                            if (pendingQrAutoJoin && pendingQrPeerName != null) {
                                findViewById<TextView>(R.id.statusText).text =
                                    "Wi-Fi Direct peer not found yet. Trying the Bluetooth route from the same QR…"
                                startBluetoothDiscovery(invitation.bluetoothDeviceName ?: invitation.deviceName, autoConnect = true)
                            }
                        }, 8_000)
                    }
                } else if ("bluetooth" in invitation.routes && ensureBluetoothPermissions()) {
                    findViewById<TextView>(R.id.statusText).text =
                        "QR found ${invitation.deviceName}. Finding its Bluetooth route…"
                    startBluetoothDiscovery(invitation.bluetoothDeviceName ?: invitation.deviceName, autoConnect = pickedFiles.isNotEmpty())
                } else {
                    findViewById<TextView>(R.id.statusText).text =
                        "This invitation has no currently usable route. Ask the receiver to refresh its QR."
                }
                return
            }
            val payload = QrBootstrap.parseUri(raw)
            findViewById<EditText>(R.id.hostInput).setText(payload.host)
            findViewById<EditText>(R.id.portInput).setText(payload.port.toString())
            findViewById<TextView>(R.id.statusText).text = buildString {
                appendLine("WarPair QR scanned successfully.")
                appendLine("Peer: ${payload.deviceName ?: "WarPair device"}")
                appendLine("Address: ${payload.host}:${payload.port}")
                payload.ssid?.let { appendLine("Wi‑Fi: $it — join this network before connecting.") }
                append("Connection details loaded.")
            }
            if (pickedFiles.isNotEmpty()) findViewById<Button>(R.id.joinButton).performClick()
            else findViewById<TextView>(R.id.statusText).append("\\nChoose files, then tap Send.")
        } catch (t: Throwable) {
            pendingScannedQr = null
            findViewById<TextView>(R.id.statusText).text =
                "This QR code is not a valid WarPair connection code: ${t.message}"
        }
    }

    private fun handleWarpairIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!data.startsWith("warpair://", ignoreCase = true)) return
        if (data.startsWith("warpair://invite?", ignoreCase = true)) {
            applyScannedQr(data)
            return
        }
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

    private fun startBluetoothDiscovery(expectedPeerName: String? = null, autoConnect: Boolean = false) {
        val status = findViewById<TextView>(R.id.statusText)
        val autoJoinTriggered = AtomicBoolean(false)
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
                    val match = expectedPeerName?.let { expected -> peers.firstOrNull { it.name.equals(expected, ignoreCase = true) } }
                    if (match != null) selectedBluetoothPeer = match
                    if (match != null && autoConnect && autoJoinTriggered.compareAndSet(false, true)) {
                        pendingQrAutoJoin = false
                        pendingQrPeerName = null
                        pendingQrHasBluetooth = false
                        findViewById<TextView>(R.id.statusText).text = "Found ${match.name} from the QR invitation. Connecting…"
                        findViewById<Button>(R.id.bluetoothJoinButton).performClick()
                    }
                }
            }
            override fun onFinished() {
                if (!autoJoinTriggered.get()) {
                    mainHandler.post { status.text = "Bluetooth scan finished. Select a device; Android may ask you to pair when connecting." }
                }
            }
            override fun onError(message: String) {
                mainHandler.post { status.text = message }
            }
        })
    }

    private fun hasWifiDirectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        val primaryActions = listOf(
            R.id.quickSendButton, R.id.quickReceiveButton, R.id.quickScanButton,
            R.id.quickFilesButton, R.id.quickToolsButton,
        ).mapNotNull { findViewById<Button?>(it) }
        primaryActions.forEach { it.isEnabled = false }
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
                primaryActions.forEach { it.isEnabled = true }
                cancelButton.isEnabled = false
                activeControl.set(null)
                activeTransportCancel.set(null)
            }
        }.start()
    }

    private fun localIpv4Endpoints(port: Int): List<QrBootstrap.Endpoint> {
        return localIpv4List().lineSequence()
            // Wi-Fi Direct has its own peer negotiation; its P2P interface address is
            // not a normal LAN endpoint until the sender joins that group.
            .filter { line ->
                val iface = line.substringBefore(": ").lowercase()
                !iface.contains("p2p") && (
                    iface.contains("wlan") || iface.contains("swlan") ||
                        iface.contains("softap") || iface.startsWith("ap") || iface.contains("eth")
                    )
            }
            .mapNotNull { line -> line.substringAfter(": ", "").trim().takeIf { it.isNotBlank() && it != "(none)" } }
            .distinct()
            .take(4)
            .map { QrBootstrap.Endpoint(it, port) }
            .toList()
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

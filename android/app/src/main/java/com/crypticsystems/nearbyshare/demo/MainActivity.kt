package com.crypticsystems.nearbyshare.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.crypticsystems.nearbyshare.NearbyCore
import java.io.ByteArrayInputStream
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private var picked: PickedFile? = null
    private lateinit var nsd: NsdNearby
    private val mainHandler = Handler(Looper.getMainLooper())

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        try {
            picked = ReceivedFiles.readPickedMeta(this, uri)
            val sizeLabel = if (picked!!.size >= 0) "${picked!!.size} bytes" else "size unknown"
            val cat = ReceivedFiles.categoryFor(picked!!.displayName).folderName
            findViewById<TextView>(R.id.pickedFileText).text =
                "Selected: ${picked!!.displayName} ($sizeLabel) → NearbyShare/$cat"
        } catch (t: Throwable) {
            picked = null
            findViewById<TextView>(R.id.pickedFileText).text =
                "Failed to read file: ${t.message}"
        }
    }

    private val requestLegacyStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        refreshLibraryStatus()
    }

    private val requestAllFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshLibraryStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nsd = NsdNearby(this)
        nsd.onPeersChanged = { peers ->
            val peersText = findViewById<TextView>(R.id.peersText)
            val hostInput = findViewById<EditText>(R.id.hostInput)
            if (peers.isEmpty()) {
                peersText.text = "Peers: (none yet)"
            } else {
                peersText.text = peers.joinToString("\n") { p ->
                    "${p.deviceName}  ${p.host}:${p.port}"
                }
                if (hostInput.text.isNullOrBlank()) {
                    hostInput.setText(peers.first().host)
                }
            }
        }

        val status = findViewById<TextView>(R.id.statusText)
        val smokeButton = findViewById<Button>(R.id.smokeButton)
        val hostButton = findViewById<Button>(R.id.hostButton)
        val joinButton = findViewById<Button>(R.id.joinButton)
        val pickFileButton = findViewById<Button>(R.id.pickFileButton)
        val discoverButton = findViewById<Button>(R.id.discoverButton)
        val libraryButton = findViewById<Button>(R.id.libraryButton)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val payloadInput = findViewById<EditText>(R.id.payloadInput)

        ensureStorageAccess()
        refreshLibraryStatus()

        libraryButton.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        pickFileButton.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        discoverButton.setOnClickListener {
            nsd.startDiscovery()
            status.text = "Discovering peers on LAN…"
        }

        smokeButton.setOnClickListener {
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton), status) {
                val report = NearbyCore.smokeTest()
                if (report.startsWith("OK")) "Smoke passed\n\n$report" else "Smoke failed\n\n$report"
            }
        }

        hostButton.setOnClickListener {
            if (!ReceivedFiles.hasWritableLibrary()) {
                status.text =
                    "Storage not writable yet.\n" +
                        "Grant All files access (Android 11+) or storage permission, then try Host again.\n\n" +
                        "Target folder: /storage/emulated/0/NearbyShare/"
                ensureStorageAccess()
                return@setOnClickListener
            }
            val name = nameInput.text?.toString()?.ifBlank { "Host" } ?: "Host"
            nsd.advertise(name, LanLink.DEFAULT_PORT)
            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton), status) {
                try {
                    LanLink.hostOnce(
                        deviceName = name,
                        onProgress = progressTo(status),
                        onOffer = LanLink.OfferDecision { offer -> askAcceptOffer(offer) },
                        openSink = { fileName -> ReceivedFiles.openIncoming(fileName) },
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
                status.text = "Enter host IP or tap Discover peers."
                return@setOnClickListener
            }
            val localPicked = picked
            val peerPort = nsd.currentPeers().firstOrNull { it.host == host }?.port
                ?: LanLink.DEFAULT_PORT

            runBg(listOf(smokeButton, hostButton, joinButton, discoverButton, libraryButton), status) {
                if (localPicked != null) {
                    ReceivedFiles.openPickedStream(this@MainActivity, localPicked.uri).use { stream ->
                        LanLink.connectOnce(
                            host = host,
                            port = peerPort,
                            deviceName = name,
                            fileName = localPicked.displayName,
                            fileSize = localPicked.size,
                            inputStream = stream,
                            onProgress = progressTo(status),
                        )
                    }
                } else {
                    val text = payloadInput.text?.toString() ?: ""
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    ByteArrayInputStream(bytes).use { stream ->
                        LanLink.connectOnce(
                            host = host,
                            port = peerPort,
                            deviceName = name,
                            fileName = "note.txt",
                            fileSize = bytes.size.toLong(),
                            inputStream = stream,
                            onProgress = progressTo(status),
                        )
                    }
                }
            }
        }
    }

    /** Blocks worker thread until user accepts or rejects (or timeout). */
    private fun askAcceptOffer(offer: LanLink.OfferRequest): Boolean {
        val latch = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val sizeLabel = if (offer.size > 0) formatSize(offer.size) else "unknown size"

        mainHandler.post {
            if (isFinishing) {
                latch.countDown()
                return@post
            }
            AlertDialog.Builder(this)
                .setTitle("Incoming file")
                .setMessage(
                    "From: ${offer.peerName}\n" +
                        "PIN: ${offer.pin}\n\n" +
                        "File: ${offer.fileName}\n" +
                        "Size: $sizeLabel\n" +
                        "Save to: NearbyShare/${offer.category.folderName}/\n\n" +
                        "Accept this transfer?",
                )
                .setCancelable(false)
                .setPositiveButton("Accept") { _, _ ->
                    accepted.set(true)
                    latch.countDown()
                }
                .setNegativeButton("Reject") { _, _ ->
                    accepted.set(false)
                    latch.countDown()
                }
                .show()
        }

        // Give the user up to 2 minutes to decide.
        val ok = latch.await(120, TimeUnit.SECONDS)
        return ok && accepted.get()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    override fun onResume() {
        super.onResume()
        if (::nsd.isInitialized) {
            refreshLibraryStatus()
        }
    }

    override fun onDestroy() {
        nsd.shutdown()
        super.onDestroy()
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    requestAllFilesAccess.launch(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    requestAllFilesAccess.launch(intent)
                }
            }
        } else {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (need.isNotEmpty()) {
                requestLegacyStorage.launch(need.toTypedArray())
            }
        }
    }

    private fun refreshLibraryStatus() {
        val status = findViewById<TextView>(R.id.statusText) ?: return
        val writable = ReceivedFiles.hasWritableLibrary()
        val root = try {
            ReceivedFiles.rootDir().absolutePath
        } catch (_: Exception) {
            "/storage/emulated/0/NearbyShare"
        }
        status.text = buildString {
            appendLine("User library (visible in Files app):")
            appendLine(root)
            appendLine("Music · Videos · Images · Documents · Archives · Other")
            appendLine(
                if (writable) "Storage: writable ✓"
                else "Storage: not writable — grant All files access / storage permission",
            )
            appendLine()
            appendLine("IPs:")
            append(localIpv4List())
        }
    }

    private fun progressTo(status: TextView): LanLink.Progress {
        return LanLink.Progress { done, total, phase ->
            val line = if (total > 0) {
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                "$phase  $done/$total ($pct%)"
            } else {
                "$phase  $done bytes"
            }
            mainHandler.post { status.text = line }
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

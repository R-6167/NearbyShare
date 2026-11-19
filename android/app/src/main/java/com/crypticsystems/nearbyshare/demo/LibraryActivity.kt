package com.crypticsystems.nearbyshare.demo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class LibraryActivity : AppCompatActivity() {
    data class Entry(
        val label: String,
        val file: File?,
        val isHeader: Boolean,
    )

    private lateinit var listView: ListView
    private lateinit var header: TextView
    private lateinit var pathView: TextView
    private var entries: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        title = "NearbyShare library"

        header = findViewById(R.id.libraryHeader)
        pathView = findViewById(R.id.libraryPath)
        listView = findViewById(R.id.libraryList)
        findViewById<Button>(R.id.refreshLibraryButton).setOnClickListener { reload() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (entry.isHeader || entry.file == null) return@setOnItemClickListener
            openFile(entry.file)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemLongClickListener false
            val file = entry.file ?: return@setOnItemLongClickListener false
            if (file.delete()) {
                Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                reload()
            } else {
                Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show()
            }
            true
        }

        reload()
    }

    private fun reload() {
        val root = try {
            ReceivedFiles.ensureLibrary()
        } catch (t: Throwable) {
            pathView.text = "Cannot open library: ${t.message}"
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("(empty)"))
            entries = emptyList()
            return
        }

        pathView.text = root.absolutePath
        val built = mutableListOf<Entry>()
        var totalFiles = 0

        for (cat in FileCategory.entries) {
            val dir = File(root, cat.folderName)
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            built += Entry("— ${cat.folderName} (${files.size}) —", null, isHeader = true)
            if (files.isEmpty()) {
                built += Entry("  (empty)", null, isHeader = true)
            } else {
                for (f in files) {
                    totalFiles++
                    built += Entry("  ${f.name}  (${formatSize(f.length())})", f, isHeader = false)
                }
            }
        }

        entries = built
        header.text = "NearbyShare library · $totalFiles file(s)\nTap to open · long-press to delete"
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            entries.map { it.label },
        )
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val mime = guessMime(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open ${file.name}"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}

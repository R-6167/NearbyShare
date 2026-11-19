package com.crypticsystems.warpair

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
    data class Entry(val label: String, val file: File?, val isHeader: Boolean)
    private lateinit var listView: ListView
    private lateinit var header: TextView
    private lateinit var pathView: TextView
    private var entries: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        title = "WarPair library"
        header = findViewById(R.id.libraryHeader)
        pathView = findViewById(R.id.libraryPath)
        listView = findViewById(R.id.libraryList)
        findViewById<Button>(R.id.refreshLibraryButton).setOnClickListener { reload() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (!entry.isHeader && entry.file != null) openFile(entry.file)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val file = entries.getOrNull(position)?.file ?: return@setOnItemLongClickListener false
            if (file.delete()) {
                Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                reload()
            }
            true
        }
        reload()
    }

    private fun reload() {
        val root = try { ReceivedFiles.ensureLibrary() } catch (t: Throwable) {
            pathView.text = t.message; return
        }
        pathView.text = root.absolutePath
        val built = mutableListOf<Entry>()
        var total = 0
        for (cat in FileCategory.entries) {
            val files = File(root, cat.folderName).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
            built += Entry("— ${cat.folderName} (${files.size}) —", null, true)
            if (files.isEmpty()) built += Entry("  (empty)", null, true)
            else files.forEach {
                total++
                built += Entry("  ${it.name}  (${it.length()} B)", it, false)
            }
        }
        entries = built
        header.text = "WarPair library · $total file(s)"
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries.map { it.label })
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Open ${file.name}"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app for this type", Toast.LENGTH_SHORT).show()
        }
    }
}

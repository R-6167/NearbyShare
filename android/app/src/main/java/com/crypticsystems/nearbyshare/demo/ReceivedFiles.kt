package com.crypticsystems.nearbyshare.demo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class PickedFile(
    val displayName: String,
    val bytes: ByteArray,
    val uri: Uri,
)

object ReceivedFiles {
    private const val DIR_NAME = "NearbyShare"

    /** Read a SAF document into memory (demo-sized files). */
    fun readPicked(context: Context, uri: Uri): PickedFile {
        val name = queryDisplayName(context, uri) ?: "file.bin"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open selected file")
        return PickedFile(name, bytes, uri)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return uri.lastPathSegment
    }

    /**
     * Save received bytes under app files and, when possible, public Downloads/NearbyShare
     * via MediaStore (no legacy storage permission required on API 29+).
     */
    fun saveReceived(context: Context, fileName: String, data: ByteArray): SaveResult {
        val safeName = sanitizeFileName(fileName)

        val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DIR_NAME)
            .also { it.mkdirs() }
        val appFile = uniqueFile(appDir, safeName)
        FileOutputStream(appFile).use { it.write(data) }

        var publicUri: Uri? = null
        var publicPathHint: String? = null
        try {
            publicUri = insertIntoDownloads(context, safeName, data)
            if (publicUri != null) {
                publicPathHint = "Downloads/$DIR_NAME/$safeName"
            }
        } catch (_: Exception) {
            // App-private path is enough for the demo.
        }

        return SaveResult(
            appPath = appFile.absolutePath,
            publicUri = publicUri?.toString(),
            publicPathHint = publicPathHint,
            bytes = data.size,
            fileName = safeName,
        )
    }

    private fun insertIntoDownloads(context: Context, fileName: String, data: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q: write under app external files only (already done).
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, guessMime(fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$DIR_NAME")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(data) }
            ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists()) return base
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$stem-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "received.bin" }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}

data class SaveResult(
    val appPath: String,
    val publicUri: String?,
    val publicPathHint: String?,
    val bytes: Int,
    val fileName: String,
) {
    fun summary(): String = buildString {
        appendLine("saved_as=$fileName")
        appendLine("app_path=$appPath")
        if (publicPathHint != null) appendLine("downloads=$publicPathHint")
        if (publicUri != null) appendLine("content_uri=$publicUri")
        appendLine("bytes=$bytes")
    }
}

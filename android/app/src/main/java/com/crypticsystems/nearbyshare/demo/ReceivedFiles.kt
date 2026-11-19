package com.crypticsystems.nearbyshare.demo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class FileCategory(val folderName: String) {
    Music("Music"),
    Videos("Videos"),
    Images("Images"),
    Documents("Documents"),
    Archives("Archives"),
    Other("Other"),
}

data class PickedFile(
    val displayName: String,
    val size: Long,
    val uri: Uri,
)

data class SaveResult(
    val appPath: String,
    val category: FileCategory,
    val fileName: String,
    val bytes: Long,
) {
    fun summary(): String = buildString {
        appendLine("category=${category.folderName}")
        appendLine("saved_as=$fileName")
        appendLine("app_path=$appPath")
        appendLine("bytes=$bytes")
    }
}

/**
 * App-private library:
 *
 * ```
 * Android/data/<pkg>/files/NearbyShare/
 *   Music/ Videos/ Images/ Documents/ Archives/ Other/
 * ```
 *
 * Received files stream directly into the matching category folder.
 */
object ReceivedFiles {
    const val ROOT_NAME = "NearbyShare"

    fun rootDir(context: Context): File {
        val root = File(context.getExternalFilesDir(null), ROOT_NAME)
        root.mkdirs()
        return root
    }

    /** Create category folders if missing. */
    fun ensureLibrary(context: Context): File {
        val root = rootDir(context)
        FileCategory.entries.forEach { cat ->
            File(root, cat.folderName).mkdirs()
        }
        return root
    }

    fun categoryFor(fileName: String): FileCategory {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
        return when (ext) {
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma" -> FileCategory.Music
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp" -> FileCategory.Videos
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> FileCategory.Images
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "rtf", "csv", "md", "json", "xml", "epub",
            -> FileCategory.Documents
            "zip", "rar", "7z", "tar", "gz", "tgz", "apk" -> FileCategory.Archives
            else -> FileCategory.Other
        }
    }

    fun categoryDir(context: Context, category: FileCategory): File {
        val dir = File(rootDir(context), category.folderName)
        dir.mkdirs()
        return dir
    }

    fun readPickedMeta(context: Context, uri: Uri): PickedFile {
        val name = queryDisplayName(context, uri) ?: "file.bin"
        val size = querySize(context, uri)
        return PickedFile(name, size, uri)
    }

    fun openPickedStream(context: Context, uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: error("Cannot open selected file")
    }

    /**
     * Open a unique destination file in the right category and return a streaming sink.
     * Chunks should be [IncomingFileSink.write]n then [IncomingFileSink.finish].
     */
    fun openIncoming(context: Context, fileName: String): IncomingFileSink {
        ensureLibrary(context)
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(context, category)
        val file = uniqueFile(dir, safe)
        val out = FileOutputStream(file)
        return IncomingFileSink(file, category, safe, out)
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

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        // Fallback: unknown size
        return -1L
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
}

class IncomingFileSink(
    val file: File,
    val category: FileCategory,
    val fileName: String,
    private val out: OutputStream,
) {
    var bytesWritten: Long = 0
        private set

    fun write(chunk: ByteArray) {
        out.write(chunk)
        bytesWritten += chunk.size
    }

    fun finish(): SaveResult {
        out.flush()
        out.close()
        return SaveResult(
            appPath = file.absolutePath,
            category = category,
            fileName = fileName,
            bytes = bytesWritten,
        )
    }

    fun abort() {
        try {
            out.close()
        } catch (_: Exception) {
        }
        file.delete()
    }
}

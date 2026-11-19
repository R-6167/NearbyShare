package com.crypticsystems.nearbyshare.demo

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
        appendLine("storage=public_NearbyShare")
    }
}

/**
 * User-visible library on shared storage (not private app sandbox,
 * not system Movies/Music/Download trees):
 *
 * ```
 * /storage/emulated/0/NearbyShare/
 *   Music/
 *   Videos/
 *   Images/
 *   Documents/
 *   Archives/
 *   Other/
 * ```
 *
 * Requires storage / all-files access on modern Android so the top-level
 * folder can be created and browsed in Files / USB.
 */
object ReceivedFiles {
    const val ROOT_NAME = "NearbyShare"

    fun rootDir(): File {
        val root = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    fun ensureLibrary(): File {
        val root = rootDir()
        FileCategory.entries.forEach { cat ->
            File(root, cat.folderName).mkdirs()
        }
        if (!root.isDirectory) {
            error(
                "Cannot create $ROOT_NAME on shared storage. " +
                    "Grant “All files access” (or storage permission) and try again.",
            )
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

    fun categoryDir(category: FileCategory): File {
        val dir = File(rootDir(), category.folderName)
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

    fun openIncoming(fileName: String): IncomingFileSink {
        ensureLibrary()
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(category)
        if (!dir.isDirectory) {
            error("Cannot write to ${dir.absolutePath}. Check storage permission.")
        }
        val file = uniqueFile(dir, safe)
        val out = FileOutputStream(file)
        return IncomingFileSink(file, category, safe, out)
    }

    fun hasWritableLibrary(): Boolean {
        return try {
            val root = ensureLibrary()
            val probe = File(root, ".write_test")
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
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

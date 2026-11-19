package com.crypticsystems.nearbyshare.demo

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
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

data class PartialInfo(
    val fileName: String,
    val expectedSize: Long,
    val bytesReceived: Long,
    val nextSeq: Long,
    val partialFile: File,
)

object ReceivedFiles {
    const val ROOT_NAME = "NearbyShare"
    private const val PARTIAL_SUFFIX = ".ns-partial"

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

    fun findPartial(fileName: String, expectedSize: Long): PartialInfo? {
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(category)
        val partial = File(dir, "$safe$PARTIAL_SUFFIX")
        if (!partial.isFile || partial.length() <= 0L) return null
        if (expectedSize > 0 && partial.length() >= expectedSize) return null
        val meta = File(dir, "$safe$PARTIAL_SUFFIX.meta")
        var nextSeq = 0L
        if (meta.isFile) {
            meta.readLines().forEach { line ->
                if (line.startsWith("nextSeq=")) {
                    nextSeq = line.removePrefix("nextSeq=").toLongOrNull() ?: 0L
                }
            }
        }
        return PartialInfo(
            fileName = safe,
            expectedSize = expectedSize,
            bytesReceived = partial.length(),
            nextSeq = nextSeq,
            partialFile = partial,
        )
    }

    fun openIncoming(fileName: String, resume: PartialInfo? = null): IncomingFileSink {
        ensureLibrary()
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(category)
        if (!dir.isDirectory) {
            error("Cannot write to ${dir.absolutePath}. Check storage permission.")
        }

        return if (resume != null && resume.partialFile.isFile) {
            val out = FileOutputStream(resume.partialFile, /* append */ true)
            IncomingFileSink(
                file = resume.partialFile,
                category = category,
                fileName = safe,
                out = out,
                bytesWritten = resume.bytesReceived,
                nextSeq = resume.nextSeq,
                isPartial = true,
            )
        } else {
            // Fresh transfer uses the real name; partials live as name.ns-partial until complete.
            val partial = File(dir, "$safe$PARTIAL_SUFFIX")
            if (partial.exists()) partial.delete()
            File(dir, "$safe$PARTIAL_SUFFIX.meta").delete()
            val out = FileOutputStream(partial)
            IncomingFileSink(
                file = partial,
                category = category,
                fileName = safe,
                out = out,
                bytesWritten = 0,
                nextSeq = 0,
                isPartial = true,
            )
        }
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

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "received.bin" }
    }
}

class IncomingFileSink(
    val file: File,
    val category: FileCategory,
    val fileName: String,
    private val out: OutputStream,
    bytesWritten: Long = 0,
    nextSeq: Long = 0,
    private val isPartial: Boolean = false,
) {
    var bytesWritten: Long = bytesWritten
        private set
    var nextSeq: Long = nextSeq
        private set

    fun write(chunk: ByteArray) {
        out.write(chunk)
        bytesWritten += chunk.size
    }

    fun noteSeq(seq: Long) {
        nextSeq = seq + 1
    }

    fun finish(): SaveResult {
        out.flush()
        out.close()
        val finalFile = if (isPartial) {
            val target = File(file.parentFile, fileName)
            val dest = if (target.exists()) uniqueSibling(target) else target
            if (!file.renameTo(dest)) {
                FileInputStream(file).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                file.delete()
            }
            File(file.parentFile, "${file.name}.meta").delete()
            dest
        } else {
            file
        }
        return SaveResult(
            appPath = finalFile.absolutePath,
            category = category,
            fileName = finalFile.name,
            bytes = bytesWritten,
        )
    }

    /** Keep partial on disk for resume; write nextSeq metadata. */
    fun parkForResume() {
        try {
            out.flush()
            out.close()
        } catch (_: Exception) {
        }
        try {
            val meta = File(file.parentFile, "${file.name}.meta")
            meta.writeText("nextSeq=$nextSeq\nbytes=$bytesWritten\n")
        } catch (_: Exception) {
        }
    }

    fun abort(keepPartial: Boolean = true) {
        try {
            out.close()
        } catch (_: Exception) {
        }
        if (keepPartial && bytesWritten > 0) {
            parkForResume()
        } else {
            file.delete()
            File(file.parentFile, "${file.name}.meta").delete()
        }
    }

    private fun uniqueSibling(base: File): File {
        val name = base.name
        val dir = base.parentFile
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
}

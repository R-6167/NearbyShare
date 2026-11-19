package com.crypticsystems.warpair

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
    Music("Music"), Videos("Videos"), Images("Images"),
    Documents("Documents"), Archives("Archives"), Other("Other"),
}

data class PickedFile(val displayName: String, val size: Long, val uri: Uri)

data class SaveResult(
    val appPath: String, val category: FileCategory, val fileName: String, val bytes: Long,
) {
    fun summary() = "category=${category.folderName}\nsaved_as=$fileName\napp_path=$appPath\nbytes=$bytes\n"
}

data class PartialInfo(
    val fileName: String, val expectedSize: Long, val bytesReceived: Long,
    val nextSeq: Long, val partialFile: File,
)

object ReceivedFiles {
    const val ROOT_NAME = "WarPair"
    private const val PARTIAL_SUFFIX = ".ns-partial"

    fun rootDir(): File {
        val root = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun ensureLibrary(): File {
        val root = rootDir()
        FileCategory.entries.forEach { File(root, it.folderName).mkdirs() }
        if (!root.isDirectory) error("Cannot create $ROOT_NAME — grant storage access")
        return root
    }

    fun categoryFor(fileName: String): FileCategory {
        val ext = fileName.lowercase().substringAfterLast('.', "")
        return when (ext) {
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> FileCategory.Music
            "mp4", "mkv", "webm", "avi", "mov", "m4v" -> FileCategory.Videos
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> FileCategory.Images
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv" -> FileCategory.Documents
            "zip", "rar", "7z", "tar", "gz", "apk" -> FileCategory.Archives
            else -> FileCategory.Other
        }
    }

    fun categoryDir(category: FileCategory) = File(rootDir(), category.folderName).also { it.mkdirs() }

    fun readPickedMeta(context: Context, uri: Uri): PickedFile {
        var name = uri.lastPathSegment ?: "file.bin"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        return PickedFile(name, size, uri)
    }

    fun openPickedStream(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Cannot open file")

    fun findPartial(fileName: String, expectedSize: Long): PartialInfo? {
        val safe = sanitizeFileName(fileName)
        val dir = categoryDir(categoryFor(safe))
        val partial = File(dir, "$safe$PARTIAL_SUFFIX")
        if (!partial.isFile || partial.length() <= 0) return null
        if (expectedSize > 0 && partial.length() >= expectedSize) return null
        var nextSeq = 0L
        File(dir, "$safe$PARTIAL_SUFFIX.meta").takeIf { it.isFile }?.readLines()?.forEach {
            if (it.startsWith("nextSeq=")) nextSeq = it.removePrefix("nextSeq=").toLongOrNull() ?: 0
        }
        return PartialInfo(safe, expectedSize, partial.length(), nextSeq, partial)
    }

    fun openIncoming(fileName: String, resume: PartialInfo? = null): IncomingFileSink {
        ensureLibrary()
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(category)
        return if (resume != null && resume.partialFile.isFile) {
            IncomingFileSink(resume.partialFile, category, safe, FileOutputStream(resume.partialFile, true), resume.bytesReceived, resume.nextSeq, true)
        } else {
            val partial = File(dir, "$safe$PARTIAL_SUFFIX")
            partial.delete(); File(dir, "$safe$PARTIAL_SUFFIX.meta").delete()
            IncomingFileSink(partial, category, safe, FileOutputStream(partial), 0, 0, true)
        }
    }

    fun hasWritableLibrary(): Boolean = try {
        val root = ensureLibrary()
        val p = File(root, ".write_test"); p.writeText("ok"); p.delete(); true
    } catch (_: Exception) { false }

    fun needsAllFilesAccess() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "received.bin" }
    }
}

class IncomingFileSink(
    val file: File, val category: FileCategory, val fileName: String,
    private val out: OutputStream, bytesWritten: Long = 0, nextSeq: Long = 0,
    private val isPartial: Boolean = false,
) {
    var bytesWritten: Long = bytesWritten; private set
    var nextSeq: Long = nextSeq; private set

    fun write(chunk: ByteArray) { out.write(chunk); bytesWritten += chunk.size }
    fun noteSeq(seq: Long) { nextSeq = seq + 1 }

    fun finish(): SaveResult {
        out.flush(); out.close()
        val finalFile = if (isPartial) {
            val target = File(file.parentFile, fileName)
            val dest = if (target.exists()) uniqueSibling(target) else target
            if (!file.renameTo(dest)) {
                FileInputStream(file).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
                file.delete()
            }
            File(file.parentFile, "${file.name}.meta").delete()
            dest
        } else file
        return SaveResult(finalFile.absolutePath, category, finalFile.name, bytesWritten)
    }

    fun parkForResume() {
        try { out.flush(); out.close() } catch (_: Exception) {}
        try { File(file.parentFile, "${file.name}.meta").writeText("nextSeq=$nextSeq\nbytes=$bytesWritten\n") } catch (_: Exception) {}
    }

    fun abort(keepPartial: Boolean = true) {
        try { out.close() } catch (_: Exception) {}
        if (keepPartial && bytesWritten > 0) parkForResume()
        else { file.delete(); File(file.parentFile, "${file.name}.meta").delete() }
    }

    private fun uniqueSibling(base: File): File {
        val name = base.name; val dir = base.parentFile
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val c = File(dir, "$stem-$i$ext"); if (!c.exists()) return c; i++
        }
    }
}

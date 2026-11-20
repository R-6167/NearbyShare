package com.crypticsystems.warpair

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import com.crypticsystems.warpair.core.NearbyCore
import java.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

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
    val nextSeq: Long, val partialFile: File, val sessionId: String,
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

    private fun partialToken(sessionId: String): String {
        require(sessionId.isNotBlank() && sessionId.length <= 256) { "invalid file session id" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun partialFile(dir: File, safeName: String, sessionId: String) =
        File(dir, "$safeName.${partialToken(sessionId)}$PARTIAL_SUFFIX")

    fun findPartial(fileName: String, expectedSize: Long, sessionId: String): PartialInfo? {
        val safe = sanitizeFileName(fileName)
        val dir = categoryDir(categoryFor(safe))
        val partial = partialFile(dir, safe, sessionId)
        val meta = File(dir, "${partial.name}.meta")
        if (!partial.isFile || partial.length() <= 0 || !meta.isFile) return null
        val values = try {
            meta.readLines().mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }.toMap()
        } catch (_: Exception) {
            return null
        }
        if (values["sessionKey"] != partialToken(sessionId)) return null
        if (values["expectedSize"]?.toLongOrNull() != expectedSize) return null
        val bytes = values["bytes"]?.toLongOrNull() ?: return null
        val nextSeq = values["nextSeq"]?.toLongOrNull() ?: return null
        if (bytes != partial.length() || bytes !in 1 until expectedSize) return null
        if (bytes % TransferFileCheckpoint.DEFAULT_CHUNK_SIZE != 0L ||
            nextSeq != bytes / TransferFileCheckpoint.DEFAULT_CHUNK_SIZE
        ) return null
        return PartialInfo(safe, expectedSize, bytes, nextSeq, partial, sessionId)
    }

    /**
     * Returns a previously committed file only when its persisted BLAKE3 digest
     * still matches. This prevents a reconnect from retransmitting a file that
     * was already committed, while refusing stale or externally modified files.
     */
    fun findCompleted(fileName: String, expectedSize: Long, sessionId: String): SaveResult? {
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(category)
        val token = partialToken(sessionId)
        val meta = File(dir, ".warpair-$token.complete")
        if (!meta.isFile) return null
        val values = meta.readLines().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        if (values["sessionKey"] != token) return null
        if (values["expectedSize"]?.toLongOrNull() != expectedSize) return null
        val originalName = decodeManifestName(values["originalName"] ?: return null) ?: return null
        if (originalName != safe) return null
        val savedName = decodeManifestName(values["savedName"] ?: return null) ?: return null
        if (sanitizeFileName(savedName) != savedName) return null
        val completed = File(dir, savedName)
        if (completed.canonicalFile.parentFile != dir.canonicalFile) return null
        if (!completed.isFile || completed.length() != expectedSize) return null
        val expectedHash = values["blake3"] ?: return null
        val actualHash = try { hashFile(completed) } catch (_: Exception) { return null }
        if (actualHash != expectedHash) return null
        return SaveResult(completed.absolutePath, category, completed.name, expectedSize)
    }

    /** Persist completion only after the receiver has verified and committed the file. */
    fun recordCompleted(
        sessionId: String,
        originalFileName: String,
        expectedSize: Long,
        save: SaveResult,
        verifiedHash: ByteArray,
    ) {
        require(verifiedHash.size == 32) { "completed file hash must be BLAKE3-256" }
        val safe = sanitizeFileName(originalFileName)
        val dir = categoryDir(categoryFor(safe)).canonicalFile
        val completed = File(save.appPath).canonicalFile
        require(completed.parentFile == dir && completed.isFile && completed.length() == expectedSize) {
            "completed file path or size does not match its transfer"
        }
        val token = partialToken(sessionId)
        val meta = File(dir, ".warpair-$token.complete")
        val temp = File(dir, ".warpair-$token.complete.tmp")
        val hash = verifiedHash.joinToString("") { "%02x".format(it) }
        val content = "sessionKey=$token\noriginalName=${encodeManifestName(safe)}\nsavedName=${encodeManifestName(completed.name)}\nexpectedSize=$expectedSize\nblake3=$hash\n"
        FileOutputStream(temp).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (meta.exists() && !meta.delete()) {
            temp.delete()
            throw java.io.IOException("could not replace completed-file manifest")
        }
        if (!temp.renameTo(meta)) {
            temp.copyTo(meta, overwrite = true)
            temp.delete()
        }
    }

    private fun encodeManifestName(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeManifestName(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) { null }

    private fun hashFile(file: File): String {
        val hasher = NearbyCore.newHasher()
        try {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) hasher.update(buffer, 0, count)
                }
            }
            return hasher.finalizeHash().joinToString("") { "%02x".format(it) }
        } finally {
            hasher.close()
        }
    }

    fun openIncoming(
        fileName: String,
        resume: PartialInfo? = null,
        sessionId: String,
        expectedSize: Long,
    ): IncomingFileSink {
        ensureLibrary()
        val safe = sanitizeFileName(fileName)
        val category = categoryFor(safe)
        val dir = categoryDir(category)
        val token = partialToken(sessionId)
        require(resume == null || (resume.sessionId == sessionId && resume.expectedSize == expectedSize)) {
            "resume checkpoint belongs to a different logical file transfer"
        }
        val partial = partialFile(dir, safe, sessionId)
        if (resume != null) {
            require(resume.partialFile.canonicalFile == partial.canonicalFile) {
                "resume checkpoint path does not match this logical file transfer"
            }
            require(partial.isFile && partial.length() == resume.bytesReceived) {
                "resume checkpoint is no longer durable"
            }
        } else {
            partial.delete()
            File(dir, "${partial.name}.meta").delete()
        }
        return IncomingFileSink(
            partial, category, safe, FileOutputStream(partial, resume != null),
            resume?.bytesReceived ?: 0L, resume?.nextSeq ?: 0L,
            expectedSize = expectedSize, sessionKey = token, isPartial = true,
        )
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
    private val out: FileOutputStream,
    bytesWritten: Long = 0, nextSeq: Long = 0,
    private val expectedSize: Long,
    private val sessionKey: String,
    private val isPartial: Boolean = false,
) {
    var bytesWritten: Long = bytesWritten; private set
    var nextSeq: Long = nextSeq; private set
    private var closed = false

    fun write(chunk: ByteArray) {
        check(!closed) { "incoming file sink is closed" }
        out.write(chunk)
        bytesWritten += chunk.size
    }

    fun noteSeq(seq: Long) { nextSeq = seq + 1 }

    private fun syncAndClose() {
        if (closed) return
        out.flush()
        out.fd.sync()
        out.close()
        closed = true
    }

    private fun writeCheckpointMetadata() {
        val meta = File(file.parentFile, "${file.name}.meta")
        val temp = File(file.parentFile, "${file.name}.meta.tmp")
        val content = "sessionKey=$sessionKey\nexpectedSize=$expectedSize\nnextSeq=$nextSeq\nbytes=$bytesWritten\n"
        FileOutputStream(temp).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (meta.exists() && !meta.delete()) {
            temp.delete()
            throw java.io.IOException("could not replace resume checkpoint metadata")
        }
        if (!temp.renameTo(meta)) {
            temp.copyTo(meta, overwrite = true)
            temp.delete()
        }
    }

    fun finish(): SaveResult {
        syncAndClose()
        val finalFile = if (isPartial) {
            val target = File(file.parentFile, fileName)
            val dest = if (target.exists()) uniqueSibling(target) else target
            if (!file.renameTo(dest)) {
                FileInputStream(file).use { i ->
                    FileOutputStream(dest).use { o -> i.copyTo(o); o.fd.sync() }
                }
                file.delete()
            }
            File(file.parentFile, "${file.name}.meta").delete()
            dest
        } else file
        return SaveResult(finalFile.absolutePath, category, finalFile.name, bytesWritten)
    }

    fun parkForResume() {
        try {
            syncAndClose()
            if (bytesWritten > 0 && expectedSize >= bytesWritten) writeCheckpointMetadata()
        } catch (_: Exception) {
            // Keep partial bytes, but never advertise an unverified checkpoint.
            File(file.parentFile, "${file.name}.meta").delete()
        }
    }

    fun abort(keepPartial: Boolean = true) {
        if (keepPartial && bytesWritten > 0) parkForResume()
        else {
            try { syncAndClose() } catch (_: Exception) {}
            file.delete()
            File(file.parentFile, "${file.name}.meta").delete()
            File(file.parentFile, "${file.name}.meta.tmp").delete()
        }
    }

    private fun uniqueSibling(base: File): File {
        val name = base.name; val dir = base.parentFile
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

package com.example.nasbrowser.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Local staging file for a download. We write to a real, seekable file inside the app's own
 * external files directory (no storage permission needed) so that large files can be resumed
 * from an arbitrary byte offset after an interruption. Only when the file is fully downloaded do
 * we move it into the public Download folder (via MediaStore).
 *
 * @param relativePath e.g. "NasFiles/C/IMG.jpg"
 */
fun partTempFile(context: Context, relativePath: String): File {
    val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    val f = File(root, ".nasparts/$relativePath.part")
    f.parentFile?.mkdirs()
    return f
}

/** Buffer used for the final local move; large enough to stay at streaming speed. */
private const val COPY_BUF = 1024 * 1024

/** Move a completed .part file into the public Download directory. Returns true on success. */
fun movePartToDownloads(
    context: Context,
    partFile: File,
    name: String,
    parent: String,
    mime: String?
): Boolean {
    return if (Build.VERSION.SDK_INT >= 29) {
        mediaStoreMove(context, partFile, name, parent, mime)
    } else {
        legacyMove(context, partFile, name, parent)
    }
}

private fun mediaStoreMove(
    context: Context,
    partFile: File,
    name: String,
    parent: String,
    mime: String?
): Boolean {
    val resolver = context.contentResolver
    val rel = "Download/$parent"

    // Remove any stale entry with the same name+path so a re-move does not duplicate it.
    try {
        val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            sel, arrayOf(name, rel), null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build()
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
        }
    } catch (_: Exception) { }

    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.RELATIVE_PATH, rel)
        if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        // A finished download still has to be copied into the public Download folder, which is a
        // full extra pass over the file. A 1 MB buffer keeps that pass at streaming speed instead
        // of paying per-syscall overhead every 64 KB.
        resolver.openOutputStream(uri)?.use { os ->
            partFile.inputStream().use { it.copyTo(os, COPY_BUF) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        partFile.delete()
        true
    } catch (e: Exception) {
        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
        false
    }
}

private fun legacyMove(context: Context, partFile: File, name: String, parent: String): Boolean {
    val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val dir = if (parent.isEmpty()) root else File(root, parent)
    if (!dir.exists()) dir.mkdirs()
    val out = File(dir, name)
    // Staging file and destination sit on the same volume, so a rename is instant and skips the
    // whole copy. Fall back to copying if the rename is refused.
    if (out.exists()) out.delete()
    if (partFile.renameTo(out)) return true
    return try {
        partFile.inputStream().use { inp ->
            out.outputStream().use { inp.copyTo(it, COPY_BUF) }
        }
        partFile.delete()
        true
    } catch (e: Exception) {
        false
    }
}

fun guessMime(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }
}

private const val BYTES_PER_M = 1024.0 * 1024.0

/** File sizes are always shown in megabytes (1 M = 1024 * 1024 bytes) with two decimals, so that
 *  every row of a directory listing and both sides of a download progress share one scale instead
 *  of each row picking its own unit. A non-empty file below 0.01 M is clamped up to 0.01 M so it
 *  never reads as "0.00 M". */
fun formatSize(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0) / BYTES_PER_M
    val shown = if (bytes > 0 && mb < 0.01) 0.01 else mb
    return String.format(Locale.US, "%.2f M", shown)
}

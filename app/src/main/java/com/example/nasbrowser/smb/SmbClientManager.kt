package com.example.nasbrowser.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.example.nasbrowser.model.SortMode
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbjFile
import java.io.InputStream
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 2/3 client (single session) used for browsing a specific share's tree.
 *
 * Unlike the original design, this manager is no longer bound to one share at construction time:
 * it connects to the server/session and lazily opens (and caches) a [DiskShare] for whichever
 * share the user navigates into. Share enumeration (the server "root") is handled separately by
 * [SmbShareLister] because smbj 0.13.0 cannot list shares.
 */
class SmbClientManager(
    private val host: String,
    private val username: String,
    private val password: String,
    private val domain: String = ""
) {
    private val config: SmbConfig = SmbConfig.builder()
        .withDialects(
            SMB2Dialect.SMB_3_1_1,
            SMB2Dialect.SMB_3_0_2,
            SMB2Dialect.SMB_3_0,
            SMB2Dialect.SMB_2_1,
            SMB2Dialect.SMB_2_0_2
        )
        .withMultiProtocolNegotiate(true)
        .withSigningRequired(false)
        .withEncryptData(false)
        // Larger read/write buffers = fewer round-trips = faster browsing/file reads.
        .withReadBufferSize(4 * 1024 * 1024)
        .withWriteBufferSize(4 * 1024 * 1024)
        .withTimeout(30, TimeUnit.SECONDS)
        .build()

    private var client: SMBClient? = null
    private var session: Session? = null
    private val shareCache = mutableMapOf<String, DiskShare>()
    private val cacheLock = Any()

    /** List sort order. Directories are always kept first; this selects the secondary key. */
    var sortMode: SortMode = SortMode.NAME_ASC

    fun connect() {
        client = SMBClient(config)
        val connection: Connection = client!!.connect(host)
        val auth = AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
        session = connection.authenticate(auth)
    }

    private fun share(name: String): DiskShare = synchronized(cacheLock) {
        shareCache[name] ?: run {
            val ds = session!!.connectShare(name) as DiskShare
            shareCache[name] = ds
            ds
        }
    }

    private fun toSmbPath(p: String): String = p.replace('/', '\\').trim('\\')

    fun list(share: String, path: String): List<SmbEntry> {
        val ds = share(share)
        val cleaned = toSmbPath(path)
        val infos: List<FileIdBothDirectoryInformation> =
            if (cleaned.isEmpty()) ds.list("") else ds.list(cleaned)
        val mapped = infos
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = (info.fileAttributes and 0x10L) != 0L
                val fullPath =
                    if (cleaned.isEmpty()) info.fileName
                    else "$cleaned/${info.fileName}".replace('\\', '/')
                SmbEntry(
                    name = info.fileName,
                    path = fullPath,
                    isDirectory = isDir,
                    size = info.endOfFile,
                    lastModified = info.changeTime?.toEpochMillis() ?: 0L
                )
            }
        // Directories always sort before files; the secondary key honors the user's sort choice.
        return mapped.sortedWith(compareBy<SmbEntry> { !it.isDirectory }.then(comparatorFor(sortMode)))
    }

    private fun comparatorFor(mode: SortMode): Comparator<SmbEntry> = when (mode) {
        SortMode.NAME_ASC -> compareBy { it.name.lowercase() }
        SortMode.NAME_DESC -> compareByDescending { it.name.lowercase() }
        SortMode.DATE_ASC -> compareBy { it.lastModified }
        SortMode.DATE_DESC -> compareByDescending { it.lastModified }
    }

    /** Recursively collect every file under [rootPath] inside [share]; returns pairs of (path, size). */
    fun collectFiles(share: String, rootPath: String): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        val stack = ArrayDeque<String>()
        stack.add(rootPath)
        while (stack.isNotEmpty()) {
            val dir = stack.removeFirst()
            for (e in list(share, dir)) {
                if (e.isDirectory) stack.add(e.path) else result.add(e.path to e.size)
            }
        }
        return result
    }

    fun openFileStream(share: String, path: String): InputStream {
        val file: SmbjFile = share(share).openFile(
            toSmbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        return file.getInputStream()
    }

    fun disconnect() {
        synchronized(cacheLock) {
            shareCache.values.forEach { try { it.close() } catch (_: Exception) {} }
            shareCache.clear()
        }
        try { session?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        session = null
        client = null
    }
}

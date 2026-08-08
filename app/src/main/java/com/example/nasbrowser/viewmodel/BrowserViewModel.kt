package com.example.nasbrowser.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nasbrowser.DownloadService
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.smb.SmbClientManager
import com.example.nasbrowser.smb.SmbConnectionPool
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.smb.SmbShareLister
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.util.TransferRepository
import com.example.nasbrowser.util.guessMime
import com.example.nasbrowser.util.movePartToDownloads
import com.example.nasbrowser.util.partTempFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbjFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.UUID

/** Result of preparing a media file locally so it can be opened by an external app. */
data class OpenResult(val file: File, val mime: String)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Connection-pool size for *browsing* (separate from the download service's pool). */
        const val CONCURRENCY = 4
        /** Application-side read buffer for SMB file transfers (1 MB -> big READs, fewer round-trips). */
        private const val READ_BUF = 1024 * 1024
    }

    private val _connection = MutableStateFlow<ConnectionParams?>(null)
    val connection = _connection.asStateFlow()

    /** The share currently being browsed, or null when showing the server's share list (root). */
    private val _currentShare = MutableStateFlow<String?>(null)
    val currentShare = _currentShare.asStateFlow()

    /** Path within the current share (ignored when _currentShare is null). */
    private val _currentPath = MutableStateFlow("")
    val currentPath = _currentPath.asStateFlow()

    private val _items = MutableStateFlow<List<SmbEntry>>(emptyList())
    val items = _items.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Set when automatic share enumeration fails; the UI then prompts for a share name. */
    private val _manualShareNeeded = MutableStateFlow(false)
    val manualShareNeeded = _manualShareNeeded.asStateFlow()

    /**
     * A selected entry together with the share it belongs to. Selections may be accumulated across
     * navigation, but only the entries that belong to the *current page* (see [visibleSelection])
     * are ever highlighted, counted, or downloaded — so a cross-directory "download selected" can
     * never pull in items from a directory the user has since left.
     */
    internal data class Selected(val entry: SmbEntry, val share: String)
    private val _selection = MutableStateFlow<LinkedHashMap<String, Selected>>(LinkedHashMap())
    internal val selection = _selection.asStateFlow()

    /**
     * The subset of [_selection] that lives on the page currently being shown (same share, direct
     * child of the current path). This is what the UI highlights/counts and what "下载选中" downloads.
     */
    internal val visibleSelection: kotlinx.coroutines.flow.StateFlow<Map<String, Selected>> =
        combine(_selection, _currentShare, _currentPath) { sel, share, path ->
            sel.filterValues { it.share == share && isDirectChild(it.entry.path, path) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _sortMode =
        MutableStateFlow(SortMode.fromKey(PrefsStore.loadSortMode(getApplication())))
    val sortMode = _sortMode.asStateFlow()

    /** Transfer list owned by [TransferRepository] (shared with the background download service). */
    val transfers = TransferRepository.transfers

    /** True while a media file is being prepared (downloaded) for external playback. */
    private val _openProgress = MutableStateFlow(false)
    val openProgress = _openProgress.asStateFlow()

    /** Set once a media file is ready on local storage; the fragment opens it and then clears it. */
    private val _openResult = MutableStateFlow<OpenResult?>(null)
    val openResult = _openResult.asStateFlow()

    private var manager: SmbClientManager? = null
    private var pool: SmbConnectionPool? = null

    init {
        TransferRepository.attach(getApplication())
    }

    private fun toSmbPath(p: String): String = p.replace('/', '\\').trim('\\')

    /**
     * True when [path] is a direct child of the directory currently shown ([curPath]):
     *  - at a share's root ([curPath] empty): the path has no '/' at all.
     *  - inside a folder: the path starts with "[curPath]/" and has no further '/'.
     */
    private fun isDirectChild(path: String, curPath: String): Boolean {
        return if (curPath.isEmpty()) {
            !path.contains('/')
        } else {
            val prefix = "$curPath/"
            path.startsWith(prefix) && !path.substring(prefix.length).contains('/')
        }
    }

    fun connect(params: ConnectionParams) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            _manualShareNeeded.value = false
            try {
                manager?.disconnect()
                pool?.close()
                manager = SmbClientManager(params.host, params.username, params.password, params.domain)
                manager!!.connect()
                manager!!.sortMode = _sortMode.value
                pool = SmbConnectionPool(params.host, params.username, params.password, params.domain, CONCURRENCY)
                _connection.value = params
                loadRoot()
            } catch (e: Exception) {
                _error.value = "连接失败: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** List the server's shares as the "root directory". Falls back to manual entry on failure. */
    private fun loadRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            try {
                clearSelection()
                val p = _connection.value ?: throw IllegalStateException("尚未连接")
                val names = SmbShareLister.listShares(p.host, p.username, p.password, p.domain)
                _items.value = names.map { share ->
                    SmbEntry(name = share, path = share, isDirectory = true, size = 0, lastModified = 0)
                }
                _currentShare.value = null
                _currentPath.value = ""
                _manualShareNeeded.value = false
            } catch (e: Exception) {
                _items.value = emptyList()
                _currentShare.value = null
                _currentPath.value = ""
                _manualShareNeeded.value = true
                _error.value = "无法自动列出共享目录: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Re-list the current view (root share list, or the current folder). */
    fun reload() {
        if (_currentShare.value == null) loadRoot() else enterFolder(_currentPath.value)
    }

    fun enterShare(share: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            try {
                val list = manager!!.list(share, "")
                _items.value = list
                _currentShare.value = share
                _currentPath.value = ""
                _manualShareNeeded.value = false
            } catch (e: Exception) {
                // Session may have been dropped (app backgrounded / NAS timeout). Auto-reconnect once.
                if (reconnect()) {
                    try {
                        val list = manager!!.list(share, "")
                        _items.value = list
                        _currentShare.value = share
                        _currentPath.value = ""
                        _manualShareNeeded.value = false
                    } catch (e2: Exception) {
                        _error.value = "打开共享失败: ${e2.message}"
                    }
                } else {
                    _error.value = "打开共享失败: ${e.message}"
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun enterFolder(path: String) {
        val share = _currentShare.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                _items.value = manager!!.list(share, path)
                _currentPath.value = path
                _error.value = null
            } catch (e: Exception) {
                // Session may have been dropped (app backgrounded / NAS timeout). Auto-reconnect once.
                if (reconnect()) {
                    try {
                        _items.value = manager!!.list(share, path)
                        _currentPath.value = path
                        _error.value = null
                    } catch (e2: Exception) {
                        _error.value = "列目录失败: ${e2.message}"
                    }
                } else {
                    _error.value = "列目录失败: ${e.message}"
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * Go up one level. When already at a share's root, returns to the server's share list.
     * The caller is responsible for the "at root (no share)" case — i.e. returning to the
     * connect screen.
     */
    fun browseUp() {
        val share = _currentShare.value ?: return
        val cur = _currentPath.value
        if (cur.isEmpty()) loadRoot() else enterFolder(cur.substringBeforeLast('/', ""))
    }

    /** The share name of the active connection (used to label the current path in the UI). */
    fun shareName(): String = _currentShare.value ?: ""

    fun toggleSelection(entry: SmbEntry) {
        val share = _currentShare.value ?: return
        // IMPORTANT: copy the current map first, then mutate the copy. Mutating the live
        // StateFlow instance in place and then assigning an equal copy makes StateFlow's
        // equals-based check suppress the emission, so the observer that enables "下载选中"
        // would never fire.
        val map = LinkedHashMap(_selection.value)
        if (map.containsKey(entry.path)) map.remove(entry.path) else map[entry.path] = Selected(entry, share)
        _selection.value = map
    }

    fun clearSelection() {
        _selection.value = LinkedHashMap()
    }

    /**
     * Change the list sort order, persist it, push it to the live SMB manager, and immediately
     * re-list the current view so the new order is visible without manually refreshing.
     */
    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        PrefsStore.saveSortMode(getApplication(), mode.key)
        manager?.sortMode = mode
        reload()
    }

    /**
     * Rebuild the SMB manager + connection pool from the saved [ConnectionParams]. Used to recover
     * from a dropped session (app backgrounded for a while, NAS idle timeout, etc.). Preserves the
     * current sort order. Returns true on success.
     */
    @Synchronized
    private fun reconnect(): Boolean {
        val p = _connection.value ?: return false
        return try {
            manager?.disconnect()
            pool?.close()
            val m = SmbClientManager(p.host, p.username, p.password, p.domain)
            m.connect()
            m.sortMode = _sortMode.value
            manager = m
            pool = SmbConnectionPool(p.host, p.username, p.password, p.domain, CONCURRENCY)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearManualShare() {
        _manualShareNeeded.value = false
    }

    /**
     * Prepare a media file for playback: download it into the app's private external-files cache
     * (`.nasopen/<share>/<path>`) so the system default viewer/player can open it via a
     * FileProvider content URI. Uses the *browsing* pool (independent of the download service).
     */
    fun openEntry(entry: SmbEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            _openProgress.value = true
            try {
                val p = pool ?: throw IllegalStateException("尚未连接")
                val share = _currentShare.value ?: throw IllegalStateException("尚未进入共享")
                val extDir = getApplication<Application>().getExternalFilesDir(null)
                    ?: getApplication<Application>().cacheDir
                val target = File(extDir, ".nasopen/$share/${entry.path}")
                target.parentFile?.mkdirs()
                p.withShare(share) { ds ->
                    val file = ds.openFile(
                        toSmbPath(entry.path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                    try {
                        target.outputStream().use { out ->
                            val buf = ByteArray(READ_BUF)
                            var offset = 0L
                            while (true) {
                                val n = file.read(buf, offset)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                offset += n
                            }
                        }
                    } finally {
                        try { file.close() } catch (_: Exception) {}
                    }
                }
                _openResult.value = OpenResult(target, guessMime(entry.path))
            } catch (e: Exception) {
                _error.value = "打开失败: ${e.message}"
            } finally {
                _openProgress.value = false
            }
        }
    }

    /**
     * Download a single entry into the app's private cache (`.nasopen/<share>/<path>`) and return
     * the local [File], reusing the shared SMB connection pool. If the file is already cached it
     * is returned immediately. Used by the image gallery to fetch each picture on demand so the
     * same connection the browser already opened is reused.
     *
     * @return the local file, or null on failure / not connected.
     */
    suspend fun fetchToCache(share: String, entry: SmbEntry): File? = withContext(Dispatchers.IO) {
        val extDir = getApplication<Application>().getExternalFilesDir(null)
            ?: getApplication<Application>().cacheDir
        val target = File(extDir, ".nasopen/$share/${entry.path}")
        if (target.exists() && target.length() > 0L) return@withContext target
        target.parentFile?.mkdirs()

        val fetch: () -> Boolean = fetch@{
            val p = pool ?: return@fetch false
            try {
                p.withShare(share) { ds ->
                    val file = ds.openFile(
                        toSmbPath(entry.path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                    try {
                        target.outputStream().use { out ->
                            val buf = ByteArray(READ_BUF)
                            var offset = 0L
                            while (true) {
                                val n = file.read(buf, offset)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                offset += n
                            }
                        }
                    } finally {
                        try { file.close() } catch (_: Exception) {}
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        // If a pooled SMB session was dropped while we were away, auto-reconnect and retry once.
        var ok = fetch()
        if (!ok && reconnect()) ok = fetch()
        if (ok) target else null
    }

    fun clearOpenResult() {
        _openResult.value = null
    }

    /**
     * Download the entries selected on the **current page** (same share, direct children of the
     * current path — see [visibleSelection]): folders are downloaded recursively, plain files are
     * downloaded as-is. Cross-directory selections are ignored, so navigating away from a folder
     * before pressing "下载选中" never drags its contents into the batch.
     *
     * The actual transfer is handed to the background [DownloadService]; this method only collects
     * the file list (using the browsing connection) and enqueues the resulting items.
     */
    fun downloadSelected() {
        // Only the current page's selection is ever downloaded.
        val sel = visibleSelection.value.values.toList()
        if (sel.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val mgr = manager ?: run { _error.value = "尚未连接"; return@launch }
            val items = mutableListOf<TransferItem>()
            try {
                for (s in sel) {
                    val e = s.entry
                    val share = s.share
                    // The selected entry's own folder name becomes the root on the phone side,
                    // e.g. "C" for path "A/B/C". We never keep the share name or its parents.
                    val base = e.path.substringAfterLast('/')
                    if (e.isDirectory) {
                        val collected = try {
                            mgr.collectFiles(share, e.path)
                        } catch (ex: Exception) {
                            // Stale browsing session — reconnect once and retry.
                            if (reconnect()) mgr.collectFiles(share, e.path) else throw ex
                        }
                        for ((fpath, fsize) in collected) {
                            val sub = fpath.removePrefix("${e.path}/")
                            val rel = buildString {
                                append("NasFiles/").append(base)
                                if (sub.isNotEmpty()) append('/').append(sub)
                            }
                            items.add(
                                TransferItem(
                                    id = UUID.randomUUID().toString(),
                                    name = fpath.substringAfterLast('/'),
                                    path = fpath,
                                    share = share,
                                    relativePath = rel,
                                    size = fsize,
                                    bytes = 0L,
                                    state = TransferState.QUEUED
                                )
                            )
                        }
                    } else {
                        items.add(
                            TransferItem(
                                id = UUID.randomUUID().toString(),
                                name = e.name,
                                path = e.path,
                                share = share,
                                relativePath = "NasFiles/$base",
                                size = e.size,
                                bytes = 0L,
                                state = TransferState.QUEUED
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _error.value = "收集文件失败: ${e.message}"
                return@launch
            }
            if (items.isEmpty()) {
                _error.value = "所选目录中没有可下载的文件"
                return@launch
            }

            TransferRepository.enqueueNew(items)
            clearSelection()
            // Hand the batch to the foreground download service; it owns its own SMB pool + threads.
            DownloadService.enqueue(getApplication(), _connection.value)
        }
    }

    /**
     * Resume / retry every transfer that is not finished. Delegated to the download service, which
     * continues from each file's existing `.part` content (断点续传).
     */
    fun resumeDownloads() {
        DownloadService.resume(getApplication(), _connection.value)
    }

    fun cancelTransfer(id: String) {
        DownloadService.cancel(getApplication(), id)
    }

    fun clearTransfers() {
        DownloadService.clear(getApplication())
    }

    /** Tear down the SMB connection and return to the disconnected (connect-screen) state. */
    fun disconnectToConnect() {
        manager?.disconnect()
        pool?.close()
        manager = null
        pool = null
        _connection.value = null
        _currentShare.value = null
        _currentPath.value = ""
        _manualShareNeeded.value = false
        clearSelection()
    }

    override fun onCleared() {
        super.onCleared()
        manager?.disconnect()
        pool?.close()
        // The download engine lives in DownloadService (its own process-scoped lifecycle), so we
        // do NOT tear it down here — that would cancel background downloads when the UI closes.
    }
}

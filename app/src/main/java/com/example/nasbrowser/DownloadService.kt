package com.example.nasbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.smb.SmbConnectionPool
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.util.TransferRepository
import com.example.nasbrowser.util.guessMime
import com.example.nasbrowser.util.movePartToDownloads
import com.example.nasbrowser.util.partTempFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.File as SmbjFile
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns the download engine.
 *
 * Why a service (not the ViewModel):
 *  - Downloads continue when the app is backgrounded (the UI may be destroyed while the service
 *    keeps running and shows a persistent notification).
 *  - The service has its OWN SMB connection pool + executor, separate from the ViewModel's
 *    *browsing* pool. Previously downloads shared the browsing pool, so when the UI re-listed a
 *    directory after returning from another app (`onResume` -> `reload` -> `reconnect`) it tore
 *    down the very connections the download threads were reading from — "threads getting messed
 *    up". That can no longer happen because the two pools are independent.
 *
 * Reconnect coordination:
 *  - Only ONE thread is ever allowed to rebuild the pool at a time (`reconnectLock` +
 *    double-checked `poolRef`). Other threads that detect a stale connection simply wait for (or
 *    reuse) the rebuilt pool and retry — no stampede, no closing a pool another thread is using.
 *
 * Resume (断点续传):
 *  - Each file is written to a seekable `.part` file; if a partial download already exists its
 *    length is used as the SMB read offset, so an interrupted transfer continues where it left off.
 *  - The transfer list is persisted to disk; after a process death the service resumes any QUEUED
 *    item from its `.part` file.
 */
class DownloadService : Service() {

    companion object {
        /** Number of files downloaded in parallel (multithreaded). */
        const val CONCURRENCY = 4
        /** Application-side read buffer for SMB file transfers (1 MB -> big READs, fewer round-trips). */
        private const val READ_BUF = 1024 * 1024
        private const val CHANNEL_ID = "nas_downloads"
        private const val NOTIF_ID = 1001
        /** Max consecutive transient-network blips before a transfer is given up as FAILED.
         *  With exponential backoff (300ms..5s, capped), the worst-case wait is roughly 75s — enough
         *  to ride out wifi roaming / NAS wake-up, but bounded enough to not hang the download. */
        private const val MAX_NET_RETRIES = 30
        /** Max consecutive non-network failures before giving up (e.g. permission errors). */
        private const val MAX_OTHER_FAILS = 10

        const val ACTION_ENQUEUE = "com.example.nasbrowser.action.ENQUEUE"
        const val ACTION_CANCEL = "com.example.nasbrowser.action.CANCEL"
        const val ACTION_REMOVE = "com.example.nasbrowser.action.REMOVE"
        const val ACTION_PAUSE_ALL = "com.example.nasbrowser.action.PAUSE_ALL"
        const val ACTION_RESUME = "com.example.nasbrowser.action.RESUME"
        const val ACTION_CLEAR = "com.example.nasbrowser.action.CLEAR"
        const val ACTION_CLEAR_DONE = "com.example.nasbrowser.action.CLEAR_DONE"
        const val EXTRA_CONN = "extra_conn"
        const val EXTRA_ID = "extra_id"

        /** Begin downloading the enqueued transfers. */
        fun enqueue(context: Context, conn: ConnectionParams?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                if (conn != null) putExtra(EXTRA_CONN, conn)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ID, id)
            }
            context.startService(intent)
        }

        /** Cancel a single transfer, drop it from the list, and delete its partial file. */
        fun remove(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_REMOVE
                putExtra(EXTRA_ID, id)
            }
            context.startService(intent)
        }

        /** Pause every not-yet-finished transfer (download in progress / queued). */
        fun pauseAll(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_PAUSE_ALL }
            context.startService(intent)
        }

        /** Resume every paused/failed transfer (continues from each .part file). */
        fun resume(context: Context, conn: ConnectionParams?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
                if (conn != null) putExtra(EXTRA_CONN, conn)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun clear(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CLEAR }
            context.startService(intent)
        }

        fun clearDone(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CLEAR_DONE }
            context.startService(intent)
        }
    }

    private val executor = Executors.newFixedThreadPool(CONCURRENCY)
    private val futures = ConcurrentHashMap<String, Future<*>>()
    /** Relative paths that currently have a worker writing their `.part` staging file.
     *
     * Note this is keyed by *relativePath*, not by transfer id: `partTempFile` names the staging
     * file after the path, so two transfer items that resolve to the same path (e.g. the same file
     * selected twice, or a duplicate restored from disk) share ONE `.part` file even though their
     * ids differ. Two workers on it each seek to their own resume offset and write forward, and
     * whichever reaches the end first moves the `.part` into Downloads — deleting it — while the
     * other is still streaming. The survivor then re-opens a fresh, empty `.part` and starts from
     * zero, so the file visibly grows, collapses and grows again instead of ever settling.
     *
     * Guarding on the shared resource (the path) rather than the id is what actually prevents
     * that. It also closes the window between `submit()` and the moment `downloadOne` flips the
     * state to ACTIVE, during which a second `startProcessing()` would otherwise still see the
     * item as QUEUED and hand it to a second worker. */
    private val activeParts = ConcurrentHashMap.newKeySet<String>()
    /** Currently-open SMB file handles, keyed by transfer id, so removeOne can force-close them. */
    private val activeFiles = ConcurrentHashMap<String, SmbjFile>()

    /** The download pool. Swapped atomically; never closed out from under an in-flight reader. */
    private val poolRef = AtomicReference<SmbConnectionPool?>(null)
    private val reconnectLock = Any()

    /** Connection used to (re)build the pool; passed by the UI, falls back to saved prefs. */
    private var lastConn: ConnectionParams? = null

    private var foregroundStarted = false

    /** 状态栏进度条已发出过的最大百分比（只增不减，见 updateNotification）。 */
    private var notifPct = 0

    private lateinit var nm: NotificationManager

    private class CancelledException : Exception()

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        TransferRepository.attach(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_ENQUEUE -> {
                lastConn = intent.getParcelableExtra(EXTRA_CONN) ?: lastConn
                // 只有「全新的一批」才把状态栏进度归零：往进行中的批次里追文件时
                // 若归零，5/5=100% → 0/7 会肉眼回跳（与 updateNotification 的
                // 只增不减策略配套）。
                if (TransferRepository.transfers.value.none {
                        it.state == TransferState.ACTIVE || it.state == TransferState.QUEUED
                    }) {
                    notifPct = 0
                }
                ensureForeground()
                startProcessing()
            }
            ACTION_CANCEL -> cancelOne(intent.getStringExtra(EXTRA_ID))
            ACTION_REMOVE -> removeOne(intent.getStringExtra(EXTRA_ID))
            ACTION_PAUSE_ALL -> pauseAll()
            ACTION_RESUME -> {
                lastConn = intent.getParcelableExtra(EXTRA_CONN) ?: lastConn
                ensureForeground()
                resumeAll()
            }
            ACTION_CLEAR -> clearAll()
            ACTION_CLEAR_DONE -> clearDone()
            else -> {
                // Restarted by the system with a null intent, or an unknown action. Resume any
                // queued work if we can rebuild a connection; otherwise just stop.
                val hasQueued = TransferRepository.transfers.value.any { it.state == TransferState.QUEUED }
                if (hasQueued) { ensureForeground(); startProcessing() } else stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "NAS 下载", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun ensureForeground() {
        foregroundStarted = true
        startForeground(NOTIF_ID, buildNotification("准备下载…", 0, 0))
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NAS 下载")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        launch?.let { builder.setContentIntent(it) }
        if (total > 0) builder.setProgress(total, done, false)
        return builder.build()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val list = TransferRepository.transfers.value
        val total = list.size
        val done = list.count { it.state == TransferState.DONE }
        val failed = list.count { it.state == TransferState.FAILED }
        val active = list.count { it.state == TransferState.ACTIVE }
        val text = when {
            active > 0 -> "下载中 $done/$total"
            failed > 0 -> "部分失败 $failed/$total，可点「继续/重试」"
            total > 0 && done == total -> "全部完成 $done/$total"
            else -> "传输列表 $done/$total"
        }
        // 进度条只增不减：下载中途再选一批文件会让 total 变大，done/total 的比例
        // 随之回退（5/5=100% → 5/12=42%），状态栏进度条肉眼可见地往回跳。
        // 记住发过的最大百分比，文字说明仍实时反映真实 done/total。
        val pct = if (total > 0) done * 100 / total else 0
        if (pct > notifPct) notifPct = pct
        nm.notify(NOTIF_ID, buildNotification(text, 100, notifPct))
        // Stop the service once there is nothing left to actively do.
        val anyPending = list.any { it.state == TransferState.ACTIVE || it.state == TransferState.QUEUED }
        if (!anyPending) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scheduling
    // ---------------------------------------------------------------------------------------------
    private fun startProcessing() {
        val pending = TransferRepository.transfers.value.filter { it.state == TransferState.QUEUED }
        for (it in pending) {
            // Skip if a worker is already writing this path's .part file — see [activeParts].
            if (!activeParts.add(it.relativePath)) continue
            val task = executor.submit {
                try {
                    runCatching { downloadOne(it) }
                } finally {
                    activeParts.remove(it.relativePath)
                }
            }
            futures[it.id] = task
        }
        if (pending.isEmpty()) updateNotification()
    }

    /** Release the [activeParts] guard for a transfer's path. Needed because a task cancelled
     *  while still queued never runs, so its own `finally` would never release it. */
    private fun releasePath(id: String) {
        TransferRepository.transfers.value.firstOrNull { it.id == id }
            ?.let { activeParts.remove(it.relativePath) }
    }

    private fun cancelOne(id: String?) {
        id ?: return
        // Mark the shared cancel flag immediately. The UI already did this too, but doing it here
        // keeps the service self-sufficient if it receives the intent before/without the UI.
        CancelHub.mark(id)
        // Interrupt the worker thread. smbj's read() ignores this, but it lets the retry-backoff
        // sleep in doRead() wake up immediately and honour the cancel flag.
        futures[id]?.cancel(true)
        // A task cancelled while still queued never runs, so its `finally` would never release the
        // path; drop it here so 继续/重试 can start a fresh worker later.
        releasePath(id)
        // Force-close ONLY this task's open SMB file handle (best effort) so a blocked read() fails
        // fast. We must NOT close the whole download pool here: every parallel download shares that
        // single pool, so tearing it down would abort ALL of them (the old "pause one, pause all" bug).
        try { activeFiles.remove(id)?.close() } catch (_: Exception) {}
        // Update transfer state to PAUSED immediately.
        TransferRepository.patch(id) { t ->
            if (t.state == TransferState.ACTIVE || t.state == TransferState.QUEUED)
                t.copy(state = TransferState.PAUSED) else t
        }
        TransferRepository.persist()
        if (foregroundStarted) updateNotification() else stopSelf()
    }

    /**
     * Cancel one transfer, drop it from the list, and delete whatever partial file it had written.
     * Only this task's SMB handle is closed — the shared download pool is left intact so other
     * parallel downloads keep going.
     */
    private fun removeOne(id: String?) {
        id ?: return
        CancelHub.mark(id)
        // Stop the worker thread and close its own SMB file handle so a blocked read() fails fast.
        futures.remove(id)?.cancel(true)
        releasePath(id)
        try { activeFiles.remove(id)?.close() } catch (_: Exception) {}
        // Delete the partial download (the residual content) for this task.
        try {
            val item = TransferRepository.transfers.value.firstOrNull { it.id == id }
            item?.let { partTempFile(application, it.relativePath).delete() }
        } catch (_: Exception) {}
        // Remove the task from the list.
        TransferRepository.remove(id)
        if (foregroundStarted) updateNotification() else stopSelf()
    }

    /** Pause every in-flight or queued transfer. Each task keeps its .part file and offset, so a
     *  later "续传/重试" resumes it from where it stopped. Only that task's own SMB handle is closed
     *  — the shared pool is left intact. */
    private fun pauseAll() {
        TransferRepository.transfers.value.forEach { t ->
            if (t.state == TransferState.ACTIVE || t.state == TransferState.QUEUED) {
                CancelHub.mark(t.id)
                futures[t.id]?.cancel(true)
                activeParts.remove(t.relativePath)
                try { activeFiles.remove(t.id)?.close() } catch (_: Exception) {}
                TransferRepository.patch(t.id) { it.copy(state = TransferState.PAUSED) }
            }
        }
        TransferRepository.persist()
        if (foregroundStarted) updateNotification() else stopSelf()
    }

    /** Resume every paused/failed transfer (断点续传). Clears any stale cancel flags so a resumed
     *  task isn't instantly re-paused by the per-chunk check in doRead. */
    private fun resumeAll() {
        TransferRepository.transfers.value.forEach { t ->
            if (t.state == TransferState.PAUSED || t.state == TransferState.FAILED) {
                CancelHub.clear(t.id)
                TransferRepository.patch(t.id) { it.copy(state = TransferState.QUEUED, error = null) }
            }
        }
        TransferRepository.persist()
        startProcessing()
    }

    private fun clearAll() {
        futures.values.forEach { try { it.cancel(true) } catch (_: Exception) {} }
        futures.clear()
        // A task that was still queued never runs, so its own `finally` never releases its path.
        // Drop every guard here, and delete the .part files too, or re-downloading the same file
        // later would be silently skipped as "already being written" and never start.
        activeParts.clear()
        CancelHub.clearAll()
        val root = application.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        root?.let { java.io.File(it, ".nasparts").deleteRecursively() }
        closeQuietly(poolRef.getAndSet(null))
        TransferRepository.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun clearDone() {
        futures.values.forEach { try { it.cancel(true) } catch (_: Exception) {} }
        futures.clear()
        // Same reasoning as clearAll(): a queued-but-never-started task would otherwise leave its
        // path guarded forever, blocking any future download of that file.
        activeParts.clear()
        CancelHub.clearAll()
        TransferRepository.clearDone()
        if (foregroundStarted) updateNotification() else stopSelf()
    }

    // ---------------------------------------------------------------------------------------------
    // Per-file download
    // ---------------------------------------------------------------------------------------------
    private fun downloadOne(item: TransferItem) {
        val cancel = CancelHub.flagFor(item.id)
        // If already cancelled (e.g. user tapped 删除 before this task started), don't begin.
        if (cancel.get()) {
            // 钳制：.part 可能已被搬走/删除，tempLen 此时是 0，直接赋值会让进度条塌回 0
            TransferRepository.patch(item.id) {
                it.copy(state = TransferState.FAILED, error = "已取消",
                        bytes = maxOf(it.bytes, tempLen(item)))
            }
            TransferRepository.persist()
            updateNotification()
            return
        }
        TransferRepository.patch(item.id) { it.copy(state = TransferState.ACTIVE, error = null) }

        var wasCancelled = false
        var lastErr: String? = null

        fun runOnce(): Boolean = try {
            doRead(item, cancel)
            true
        } catch (e: CancelledException) {
            wasCancelled = true
            false
        } catch (e: Exception) {
            lastErr = friendlyError(e)
            false
        }

        var ok = runOnce()

        // If the pool was closed by cancelOne, skip retry entirely.
        if (!ok && cancel.get()) {
            wasCancelled = true
        }

        // Only retry if not cancelled and first attempt failed (stale SMB session handling).
        if (!ok && !wasCancelled) {
            // ⚠️ 不要在这里强行拆掉共享池（旧代码：CAS 成 null 再 ensurePool(stale)  close 它）。
            // 4 个下载共用同一根池子，关掉会让另外 3 个的在途 read 全部失败、集体走一遍
            // 「重建 + 重试」，表现为所有条目同时闪"网络中断 重试中"、进度肉眼可见地反复跳。
            // doRead 内部每一轮 attempt 都会 ensurePool() 并在失败时自愈，这里只要保证
            // 池子存在就够了；真的需要重建时由 doRead 的异常分支精准拆除。
            ensurePool()
            ok = runOnce()
            // After retry, check cancel flag again.
            if (cancel.get()) {
                wasCancelled = true
            }
        }

        when {
            // 钳制 tempLen：取消/暂停发生时 .part 可能刚被搬进 Downloads（长度为 0），
            // 不钳制会把已经跑完的进度一夜打回 0%。
            wasCancelled -> TransferRepository.patch(item.id) {
                it.copy(state = TransferState.PAUSED,
                        bytes = maxOf(it.bytes, tempLen(item)))
            }
            // Deliberately leaves `bytes` alone: doRead's last progress patch already holds the
            // real delivered size. Overwriting it with `item.size` — a snapshot taken when this
            // task was enqueued — would make the bar jump at the very moment it completes.
            ok -> TransferRepository.patch(item.id) {
                it.copy(state = TransferState.DONE, error = null)
            }
            else -> TransferRepository.patch(item.id) {
                it.copy(state = TransferState.FAILED, error = lastErr ?: "下载失败",
                        bytes = maxOf(it.bytes, tempLen(item)))
            }
        }
        TransferRepository.persist()
        updateNotification()
    }

    private fun tempLen(item: TransferItem): Long {
        val t = partTempFile(application, item.relativePath)
        return if (t.exists()) t.length() else 0L
    }

    /**
     * Get the current pool, (re)building it if necessary. Coordinated so that only one thread ever
     * rebuilds at a time. If [stale] is the pool already in use and it was marked stale, it is
     * closed here exactly once; other callers that detect the new pool return it without closing.
     */
    private fun ensurePool(stale: SmbConnectionPool? = null): SmbConnectionPool? {
        val conn = lastConn ?: run {
            val prefs = PrefsStore.load(application)
            if (prefs.host.isEmpty()) null
            else ConnectionParams(prefs.host, prefs.user, prefs.pass, prefs.domain)
        } ?: return null

        synchronized(reconnectLock) {
            val cur = poolRef.get()
            if (cur != null && cur !== stale) return cur
            // (re)build: close the stale pool exactly once, then install the new one.
            closeQuietly(stale)
            val np = try {
                SmbConnectionPool(conn.host, conn.username, conn.password, conn.domain, CONCURRENCY)
            } catch (e: Exception) {
                null
            }
            poolRef.set(np)
            return np
        }
    }

    /**
     * Throw [CancelledException] if this task was cancelled or its thread was interrupted.
     *
     * `Thread.interrupted()` clears the interrupt flag as a side effect, so it has to be evaluated
     * on every call. Writing the test as `cancel.get() || Thread.interrupted()` short-circuits: on
     * a cancel the flag is left set, and because the executor reuses threads the next task to land
     * on that thread would see a stale interrupt and abort instantly.
     */
    private fun checkCancelled(cancel: AtomicBoolean) {
        val interrupted = Thread.interrupted()
        if (cancel.get() || interrupted) throw CancelledException()
    }

    /**
     * Read a single file's bytes into its `.part` staging file, resuming from any existing partial
     * content. Returns true on success; throws [CancelledException] if cancelled mid-way or another
     * exception (e.g. a stale SMB session) on failure.
     *
     * Two invariants keep a resume from drifting:
     *  - The write offset is ALWAYS re-derived from the real length of the `.part` file on disk at
     *    the start of every attempt — never carried over in memory from a previous attempt, so a
     *    reconnect can't resume from a stale cursor.
     *  - Every chunk is fetched with an ABSOLUTE-offset SMB READ (`file.read(buf, offset)`) rather
     *    than a sequential stream positioned by `skip(offset)`. smbj's `FileInputStream.skip`
     *    returns the requested `n` unconditionally (verified: the method ends in `lload_1;
     *    lreturn`), so a caller can never tell whether the cursor actually landed where it asked —
     *    and it works by mutating an internal cursor plus discarding the read-ahead buffer. Stating
     *    the absolute offset on *every* request removes that state from the picture entirely: an
     *    interrupted transfer always continues from the byte right after the last one written.
     *
     * Network blips (wifi stalls, NAS wake-ups, broken pipes, timeouts) are NOT treated as fatal:
     * the affected file handle is closed, the SMB pool is rebuilt, and the read resumes from the
     * last good offset. The user only sees a brief "网络中断 重试中" hint on the transfer item.
     * Only after [MAX_NET_RETRIES] consecutive network blips does the transfer fall back to FAILED.
     */
    private fun doRead(item: TransferItem, cancel: AtomicBoolean): Boolean {
        val share = item.share ?: throw IllegalStateException("缺少共享名")
        val tempFile = partTempFile(application, item.relativePath)
        val parent = item.relativePath.substringBeforeLast('/')
        val mime = guessMime(item.path)
        val raf = RandomAccessFile(tempFile, "rw")
        try {
            var netRetries = 0
            var otherFails = 0
            outer@ while (true) {
                checkCancelled(cancel)
                val p = ensurePool()
                if (p == null) {
                    if (cancel.get()) throw CancelledException()
                    Thread.sleep(1000)
                    continue@outer
                }
                // Authoritative resume point: whatever is really on disk right now.
                var offset = raf.length()
                var ds: com.hierynomus.smbj.share.DiskShare? = null
                try {
                    ds = p.acquireShare(share)
                    // The server's own end-of-file is the only trustworthy completion threshold.
                    // item.size comes from an earlier directory listing and can be stale or 0; using
                    // it as the gate lets a *partial* .part be declared complete, moved into the
                    // public Download folder and then re-downloaded from zero — the file size
                    // apparently bouncing up and down instead of settling.
                    var remoteSize = 0L
                    try {
                        remoteSize = ds.getFileInformation(
                            toSmbPath(item.path),
                            FileStandardInformation::class.java
                        ).endOfFile
                    } catch (_: Exception) { /* fall back to item.size below */ }
                    if (remoteSize <= 0) remoteSize = item.size

                    if (remoteSize > 0 && offset >= remoteSize) {
                        // Already complete (e.g. we crashed mid-final-move on a previous run):
                        // deliver it and finish — never re-download.
                        TransferRepository.patchProgress(item.id, remoteSize)
                        // raf is deliberately NOT closed here: RandomAccessFile is unbuffered, so the
                        // bytes are already on disk, and keeping it open means a retry can still ask
                        // for raf.length(). The outer finally closes it on every exit path.
                        if (!movePartToDownloads(application, tempFile, item.name, parent, mime))
                            throw IOException("写入下载目录失败")
                        return true
                    }
                    // ---- Inner read loop (one attempt; on error, outer rebuilds and retries) ----
                    var file: SmbjFile? = null
                    var lastReport = offset
                    var lastReportAt = 0L
                    try {
                        file = ds.openFile(
                            toSmbPath(item.path),
                            EnumSet.of(AccessMask.GENERIC_READ),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_OPEN,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                        )
                        activeFiles[item.id] = file
                        val buf = ByteArray(READ_BUF)
                        while (true) {
                            checkCancelled(cancel)
                            // Absolute-offset READ: no stream cursor, no skip(), no drift.
                            val n = file.read(buf, offset)
                            if (n <= 0) break
                            raf.seek(offset)
                            raf.write(buf, 0, n)
                            offset += n
                            // Real progress resets both budgets, so only *consecutive* failures
                            // count toward the limits (netRetries used to leak across the whole
                            // transfer, so a long download over flaky wifi eventually gave up).
                            netRetries = 0
                            otherFails = 0
                            val now = System.currentTimeMillis()
                            if (offset - lastReport >= 256 * 1024 && now - lastReportAt >= 500) {
                                lastReport = offset
                                lastReportAt = now
                                TransferRepository.patchProgress(item.id, offset)
                            }
                        }
                        // Reaching EOF short of the server's size means the transfer ended early —
                        // never deliver a truncated file as "done"; let the retry path resume it.
                        // This is what a dropped connection actually looks like from here, so it
                        // must be classified as a network blip (SocketException), otherwise it
                        // falls into the non-transient branch: three 300 ms retries and then a
                        // permanent FAILED, so every network hiccup would end the transfer
                        // instead of resuming it.
                        if (remoteSize > 0 && offset < remoteSize)
                            throw SocketException("连接提前结束（${offset}/${remoteSize}）")
                        // Success.
                        TransferRepository.patchProgress(item.id, offset)
                        try { file.close() } catch (_: Exception) {}
                        file = null
                        if (!movePartToDownloads(application, tempFile, item.name, parent, mime))
                            throw IOException("写入下载目录失败")
                        return true
                    } finally {
                        activeFiles.remove(item.id)
                        try { file?.close() } catch (_: Exception) {}
                    }
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    checkCancelled(cancel)
                    if (isTransientNetwork(e)) {
                        if (++netRetries > MAX_NET_RETRIES) throw e
                        showNetRetry(item.id, netRetries)
                        try { raf.seek(offset) } catch (_: Exception) {}
                        // Rebuild the SMB pool so the next acquireShare gets fresh sessions.
                        val stale = poolRef.getAndSet(null)
                        closeQuietly(stale)
                        try { Thread.sleep(netBackoffMs(netRetries)) } catch (_: InterruptedException) {
                            if (cancel.get()) throw CancelledException()
                        }
                        continue@outer
                    }
                    // Non-network failure: small bounded retry, then bubble up to FAILED.
                    if (++otherFails > MAX_OTHER_FAILS) throw e
                    try { raf.seek(offset) } catch (_: Exception) {}
                    try { Thread.sleep(300) } catch (_: InterruptedException) {
                        if (cancel.get()) throw CancelledException()
                    }
                    continue@outer
                } finally {
                    // Another thread's network blip may have torn down (and closed) this pool
                    // while we were using it. A throw from here would replace the real exception
                    // with a pool-closed error and send the retry logic down the wrong branch.
                    try { ds?.let { p.releaseShare(share, it) } } catch (_: Exception) {}
                }
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    private fun isTransientNetwork(t: Throwable): Boolean {
        var cur: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            if (cur is CancelledException) return false
            if (cur is java.util.concurrent.CancellationException) return false
            when {
                cur is TimeoutException -> return true
                cur is SocketException -> return true
                cur is UnknownHostException -> return true
                cur is SMBRuntimeException -> return true
                cur is TransportException -> return true
                cur is IOException -> {
                    val m = cur.message ?: ""
                    if (m.contains("Broken pipe", true) ||
                        m.contains("Connection reset", true) ||
                        m.contains("Connection closed", true) ||
                        m.contains("Connection aborted", true) ||
                        m.contains("Software caused connection abort", true) ||
                        m.contains("Read timed out", true) ||
                        m.contains("Unexpected end of stream", true)) return true
                }
            }
            cur = cur.cause
        }
        return false
    }

    private fun netBackoffMs(retry: Int): Long {
        // 300, 600, 1200, 2400, 4800, 5000... (capped at 5s). retry starts at 1.
        val shift = (retry - 1).coerceAtLeast(0).coerceAtMost(4)
        return (300L shl shift).coerceAtMost(5000L)
    }

    /** Update the transfer item's transient status to indicate an active network retry. */
    private fun showNetRetry(itemId: String, retry: Int) {
        TransferRepository.patch(itemId) { it.copy(error = "网络中断 重试中 ($retry)") }
    }

    private fun friendlyError(e: Throwable): String {
        if (isTransientNetwork(e)) return "网络中断"
        val msg = e.message ?: e.javaClass.simpleName
        return if (msg.length > 60) msg.substring(0, 60) + "…" else msg
    }

    private fun toSmbPath(p: String): String = p.replace('/', '\\').trim('\\')

    private fun closeQuietly(p: SmbConnectionPool?) {
        try { p?.close() } catch (_: Exception) {}
    }
}

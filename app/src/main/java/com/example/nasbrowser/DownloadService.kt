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
import com.hierynomus.smbj.share.File as SmbjFile
import java.io.RandomAccessFile
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

        const val ACTION_ENQUEUE = "com.example.nasbrowser.action.ENQUEUE"
        const val ACTION_CANCEL = "com.example.nasbrowser.action.CANCEL"
        const val ACTION_PAUSE_ALL = "com.example.nasbrowser.action.PAUSE_ALL"
        const val ACTION_RESUME = "com.example.nasbrowser.action.RESUME"
        const val ACTION_CLEAR = "com.example.nasbrowser.action.CLEAR"
        const val EXTRA_CONN = "extra_conn"
        const val EXTRA_ID = "extra_id"

        /** Begin (or continue) downloading the enqueued transfers. */
        fun enqueue(context: Context, conn: ConnectionParams?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                if (conn != null) putExtra(EXTRA_CONN, conn)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Resume paused transfers and retry failed ones. */
        fun resume(context: Context, conn: ConnectionParams?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
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

        fun pauseAll(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_PAUSE_ALL }
            context.startService(intent)
        }

        fun clear(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CLEAR }
            context.startService(intent)
        }
    }

    private val executor = Executors.newFixedThreadPool(CONCURRENCY)
    private val futures = ConcurrentHashMap<String, Future<*>>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** The download pool. Swapped atomically; never closed out from under an in-flight reader. */
    private val poolRef = AtomicReference<SmbConnectionPool?>(null)
    private val reconnectLock = Any()

    /** Connection used to (re)build the pool; passed by the UI, falls back to saved prefs. */
    private var lastConn: ConnectionParams? = null

    private var foregroundStarted = false

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
                ensureForeground()
                startProcessing()
            }
            ACTION_RESUME -> {
                lastConn = intent.getParcelableExtra(EXTRA_CONN) ?: lastConn
                // Re-queue paused transfers and retry failed ones (keep finished ones done).
                TransferRepository.transfers.value.forEach { t ->
                    if (t.state == TransferState.FAILED || t.state == TransferState.PAUSED) {
                        TransferRepository.patch(t.id) { it.copy(state = TransferState.QUEUED, error = null) }
                    }
                }
                TransferRepository.persist()
                ensureForeground()
                startProcessing()
            }
            ACTION_CANCEL -> cancelOne(intent.getStringExtra(EXTRA_ID))
            ACTION_PAUSE_ALL -> pauseAll()
            ACTION_CLEAR -> clearAll()
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
        nm.notify(NOTIF_ID, buildNotification(text, done, total))
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
            val task = executor.submit { runCatching { downloadOne(it) } }
            futures[it.id] = task
        }
        if (pending.isEmpty()) updateNotification()
    }

    private fun cancelOne(id: String?) {
        id ?: return
        cancelFlags[id]?.set(true)
        futures[id]?.cancel(true)
        TransferRepository.markCancelled(id)
        if (foregroundStarted) updateNotification() else stopSelf()
    }

    private fun pauseAll() {
        TransferRepository.transfers.value.forEach { t ->
            if (t.state == TransferState.ACTIVE || t.state == TransferState.QUEUED) {
                cancelFlags[t.id]?.set(true)
                futures[t.id]?.cancel(true)
                TransferRepository.patch(t.id) { it.copy(state = TransferState.PAUSED) }
            }
        }
        TransferRepository.persist()
        if (foregroundStarted) updateNotification() else stopSelf()
    }

    private fun clearAll() {
        futures.values.forEach { try { it.cancel(true) } catch (_: Exception) {} }
        futures.clear()
        cancelFlags.clear()
        val root = application.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        root?.let { java.io.File(it, ".nasparts").deleteRecursively() }
        closeQuietly(poolRef.getAndSet(null))
        TransferRepository.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------------
    // Per-file download
    // ---------------------------------------------------------------------------------------------
    private fun downloadOne(item: TransferItem) {
        val cancel = cancelFlags.getOrPut(item.id) { AtomicBoolean(false) }
        cancel.set(false)
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
            lastErr = e.message ?: "下载失败"
            false
        }

        var ok = runOnce()
        if (!ok && !wasCancelled) {
            // Stale SMB session — mark the current pool stale so a single thread rebuilds it, then
            // retry once with the (re)built pool. Other threads that also saw the stale pool will
            // simply reuse this rebuild instead of racing to recreate it.
            val p = poolRef.get()
            if (p != null) poolRef.compareAndSet(p, null)
            ensurePool(p)
            ok = runOnce()
        }

        when {
            wasCancelled -> TransferRepository.patch(item.id) {
                it.copy(state = TransferState.PAUSED, bytes = tempLen(item))
            }
            ok -> TransferRepository.patch(item.id) {
                it.copy(state = TransferState.DONE, bytes = item.size, error = null)
            }
            else -> TransferRepository.patch(item.id) {
                it.copy(state = TransferState.FAILED, error = lastErr ?: "下载失败", bytes = tempLen(item))
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
     * Read a single file's bytes into its `.part` staging file, resuming from any existing partial
     * content. Returns true on success; throws [CancelledException] if cancelled mid-way or another
     * exception (e.g. a stale SMB session) on failure.
     */
    private fun doRead(item: TransferItem, cancel: AtomicBoolean): Boolean {
        val p = ensurePool() ?: throw IllegalStateException("尚未连接")
        val share = item.share ?: throw IllegalStateException("缺少共享名")
        val tempFile = partTempFile(application, item.relativePath)
        val parent = item.relativePath.substringBeforeLast('/')
        val mime = guessMime(item.path)
        p.withShare(share) { ds ->
            val remoteSize = try {
                ds.getFileInformation(toSmbPath(item.path), FileStandardInformation::class.java).endOfFile
            } catch (e: Exception) {
                item.size
            }

            val existing = if (tempFile.exists()) tempFile.length() else 0L
            // Already fully downloaded but interrupted before the final move? Just move it.
            if (remoteSize > 0 && existing >= remoteSize) {
                movePartToDownloads(application, tempFile, item.name, parent, mime)
            } else {
                val startOffset = if (remoteSize > 0 && existing in 1 until remoteSize) existing else 0L
                var raf: RandomAccessFile? = null
                var file: SmbjFile? = null
                try {
                    raf = RandomAccessFile(tempFile, "rw")
                    raf.seek(startOffset)
                    file = ds.openFile(
                        toSmbPath(item.path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                    val buf = ByteArray(READ_BUF)
                    var offset = startOffset
                    var lastReport = startOffset
                    while (true) {
                        if (cancel.get()) throw CancelledException()
                        val n = file.read(buf, offset)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        offset += n
                        if (offset - lastReport >= 256 * 1024) {
                            lastReport = offset
                            TransferRepository.patch(item.id) { it.copy(bytes = offset) }
                        }
                    }
                    TransferRepository.patch(item.id) { it.copy(bytes = offset) }
                    try { file.close() } catch (_: Exception) {}
                    try { raf.close() } catch (_: Exception) {}
                    movePartToDownloads(application, tempFile, item.name, parent, mime)
                } finally {
                    try { file?.close() } catch (_: Exception) {}
                    try { raf?.close() } catch (_: Exception) {}
                }
            }
        }
        return true
    }

    private fun toSmbPath(p: String): String = p.replace('/', '\\').trim('\\')

    private fun closeQuietly(p: SmbConnectionPool?) {
        try { p?.close() } catch (_: Exception) {}
    }
}

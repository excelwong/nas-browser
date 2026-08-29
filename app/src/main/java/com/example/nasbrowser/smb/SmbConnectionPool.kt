package com.example.nasbrowser.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A pool of authenticated SMB (DiskShare) connections used for concurrent downloads.
 *
 * The pool is now share-aware: each distinct share gets its own bounded queue of [size]
 * connections, so multiple files can be pulled in parallel (each on its own SMB session) and
 * the pool transparently serves whichever share a download belongs to.
 */
class SmbConnectionPool(
    private val host: String,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val size: Int = 3
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
        // Larger read/write buffers let smbj fetch up to several MB per SMB READ/WRITE, which
        // drastically cuts round-trips (and therefore transfer time) over high-latency links.
        .withReadBufferSize(4 * 1024 * 1024)
        .withWriteBufferSize(4 * 1024 * 1024)
        // Per-request timeout. This used to be 3s to bound how long a cancel took to bite, but a
        // NAS waking a spun-down disk (or a brief wifi stall) easily exceeds that, and every such
        // timeout costs a failed request + backoff + re-read — i.e. it made transfers SLOWER.
        // Cancellation no longer depends on it: the transfer is dropped from the list instantly and
        // the worker stops via the per-chunk cancel flag plus a forced close of its file handle.
        .withTimeout(20, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(config)
    private val queues = mutableMapOf<String, LinkedBlockingQueue<DiskShare>>()
    private val queuesLock = Any()
    private val created = mutableListOf<Pair<Session, Connection>>()
    /** Track all DiskShare objects ever created, including borrowed ones, for forced close. */
    private val allShares = mutableListOf<DiskShare>()
    private val allSharesLock = Any()

    private fun getQueue(share: String): LinkedBlockingQueue<DiskShare> = synchronized(queuesLock) {
        queues[share] ?: run {
            val q = LinkedBlockingQueue<DiskShare>(size)
            repeat(size) { q.add(createShare(share)) }
            synchronized(allSharesLock) { allShares.addAll(q.toList()) }
            queues[share] = q
            q
        }
    }

    private fun createShare(share: String): DiskShare {
        val conn = client.connect(host)
        val session = conn.authenticate(
            AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
        )
        created.add(session to conn)
        return session.connectShare(share) as DiskShare
    }

    /** Borrow a dedicated SMB share for [share] for the duration of [block]; safe for concurrent use. */
    fun <T> withShare(share: String, block: (DiskShare) -> T): T {
        val q = getQueue(share)
        val ds = q.take()
        try {
            return block(ds)
        } finally {
            q.put(ds)
        }
    }

    /** Acquire a share without the auto-release scope; pair with [releaseShare] once done. Use this
     *  when the caller's recovery logic needs to throw out of the borrow scope (e.g. network
     *  errors that should rebuild the pool and retry). */
    fun acquireShare(share: String): DiskShare = getQueue(share).take()

    fun releaseShare(share: String, ds: DiskShare) {
        val q = synchronized(queuesLock) { queues[share] } ?: run {
            try { ds.close() } catch (_: Exception) {}
            return
        }
        // If the pool was closed (queue cleared mid-flight), drop the share rather than re-queueing.
        if (!q.offer(ds)) {
            try { ds.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        synchronized(allSharesLock) {
            allShares.toList().forEach { ds -> try { ds.close() } catch (_: Exception) {} }
            allShares.clear()
        }
        queues.values.forEach { q ->
            q.clear()
        }
        queues.clear()
        created.forEach { (session, conn) ->
            try { session.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
        created.clear()
        try { client.close() } catch (_: Exception) {}
    }
}

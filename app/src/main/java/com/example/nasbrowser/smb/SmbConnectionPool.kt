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
        .withTimeout(30, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(config)
    private val queues = mutableMapOf<String, LinkedBlockingQueue<DiskShare>>()
    private val queuesLock = Any()
    private val created = mutableListOf<Pair<Session, Connection>>()

    private fun getQueue(share: String): LinkedBlockingQueue<DiskShare> = synchronized(queuesLock) {
        queues[share] ?: run {
            val q = LinkedBlockingQueue<DiskShare>(size)
            repeat(size) { q.add(createShare(share)) }
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

    fun close() {
        queues.values.forEach { q ->
            q.toList().forEach { ds -> try { ds.close() } catch (_: Exception) {} }
        }
        queues.clear()
        created.forEach { (session, conn) ->
            try { session.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
        try { client.close() } catch (_: Exception) {}
    }
}

package com.example.nasbrowser.util

import android.app.Application
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Single source of truth for the in-progress / finished transfer list. Shared between the UI
 * ([com.example.nasbrowser.viewmodel.BrowserViewModel]) and the background
 * [com.example.nasbrowser.DownloadService] so progress stays consistent no matter which component
 * updates it. Persisted to disk via [TransferStore] after every durable change (enqueue / final
 * state / clear) so an interrupted download can be resumed — but NOT on every byte-progress patch,
 * which keeps disk writes cheap during large transfers.
 */
object TransferRepository {
    private var app: Application? = null
    private val lock = Any()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers = _transfers.asStateFlow()

    /** True once the persisted transfers have been loaded into memory (idempotent). */
    private var loaded = false

    /** Load persisted transfers into memory. Safe to call repeatedly; only the first call loads. */
    fun attach(application: Application) {
        if (app == null) app = application
        if (loaded) return
        val list = TransferStore.load(application)
        // Anything not finished is resumable after a restart: normalize to QUEUED.
        _transfers.value = list.map { t ->
            if (t.state != TransferState.DONE) t.copy(state = TransferState.QUEUED, error = null) else t
        }
        loaded = true
    }

    fun replaceAll(items: List<TransferItem>) {
        synchronized(lock) { _transfers.value = items }
        persist()
    }

    /** Add [items] that are not already present (matched by remote path). Used when enqueuing. */
    fun enqueueNew(items: List<TransferItem>) {
        synchronized(lock) {
            val have = _transfers.value.map { it.path }.toSet()
            val fresh = items.filter { it.path !in have }
            if (fresh.isNotEmpty()) _transfers.value = _transfers.value + fresh
        }
        persist()
    }

    /**
     * Mutate a single transfer in place. Does NOT persist by itself (progress patches are frequent);
     * call [persist] when the change is durable (final state, cancel, clear).
     */
    fun patch(id: String, transform: (TransferItem) -> TransferItem) {
        synchronized(lock) {
            val cur = _transfers.value.toMutableList()
            val i = cur.indexOfFirst { it.id == id }
            if (i >= 0) {
                cur[i] = transform(cur[i])
                _transfers.value = cur
            }
        }
    }

    /** Mark a transfer cancelled/paused (only if it was actively running or queued). */
    fun markCancelled(id: String) {
        patch(id) { t ->
            if (t.state == TransferState.ACTIVE || t.state == TransferState.QUEUED)
                t.copy(state = TransferState.PAUSED) else t
        }
        persist()
    }

    fun clear() {
        synchronized(lock) { _transfers.value = emptyList() }
        persist()
    }

    /** Force a disk write of the current transfer list. */
    fun persist() {
        app?.let { TransferStore.save(it, _transfers.value) }
    }
}

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
        val normalized = list.map { t ->
            if (t.state != TransferState.DONE) t.copy(state = TransferState.QUEUED, error = null) else t
        }
        // Also drop duplicate paths that may already be sitting in the saved list from an older
        // build: two items for one path share a single .part file and would fight over it. The
        // first occurrence wins.
        val seen = mutableSetOf<String>()
        // The saved `bytes` is stale: persist() deliberately skips byte-progress updates, so it
        // still holds whatever was current at the last *state* change — often 0. Showing that
        // after a restart makes the completion rate collapse to 0% and then jump back up as soon
        // as doRead reports real progress, which looks like the download bouncing up and down.
        // The .part file on disk is the only trustworthy record of how much we actually have.
        _transfers.value = normalized.filter { seen.add(it.path) }.map { t ->
            if (t.state == TransferState.DONE) t
            else {
                val have = try {
                    partTempFile(application, t.relativePath).let { if (it.exists()) it.length() else 0L }
                } catch (_: Exception) { 0L }
                if (have > t.bytes) t.copy(bytes = have) else t
            }
        }
        loaded = true
    }

    fun replaceAll(items: List<TransferItem>) {
        synchronized(lock) { _transfers.value = items }
        persist()
    }

    /** Add [items] that are not already present (matched by remote path). Used when enqueuing.
     * Skips items whose path already exists in the list, regardless of state,
     * to prevent duplicate entries. */
    fun enqueueNew(items: List<TransferItem>) {
        synchronized(lock) {
            val seen = _transfers.value.mapTo(mutableSetOf()) { it.path }
            val fresh = ArrayList<TransferItem>(items.size)
            for (it in items) {
                // De-duplicate against the existing list AND within this batch. `seen.add` is
                // checked as we go, so a path repeated inside `items` is still only taken once —
                // the old `filter` compared every candidate against the list as it was *before*
                // the batch, so the same file selected twice slipped in as two items (different
                // ids, same path) that then shared one .part file.
                if (seen.add(it.path)) fresh.add(it)
            }
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

    /** Remove a single transfer by id (used when the user deletes a task). */
    fun remove(id: String) {
        synchronized(lock) { _transfers.value = _transfers.value.filter { it.id != id } }
        persist()
    }

    /** Remove only transfers that have reached DONE state. */
    fun clearDone() {
        synchronized(lock) {
            _transfers.value = _transfers.value.filter { it.state != TransferState.DONE }
        }
        persist()
    }

    /** Force a disk write of the current transfer list. */
    fun persist() {
        app?.let { TransferStore.save(it, _transfers.value) }
    }
}

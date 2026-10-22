package com.example.nasbrowser.util

import android.content.Context
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the in-progress / finished transfer list as JSON so an interrupted download can be
 * resumed after the app is killed and reopened. Stored in the app's private files dir.
 */
object TransferStore {
    private const val FILE = "transfers.json"

    fun save(context: Context, items: List<TransferItem>) {
        try {
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("id", it.id)
                o.put("name", it.name)
                o.put("path", it.path)
                o.put("share", it.share ?: "")
                o.put("relativePath", it.relativePath)
                o.put("size", it.size)
                o.put("bytes", it.bytes)
                o.put("state", it.state.name)
                o.put("error", it.error ?: "")
                arr.put(o)
            }
            context.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(arr.toString().toByteArray())
            }
        } catch (_: Exception) {
            // best-effort persistence; ignore failures
        }
    }

    fun load(context: Context): List<TransferItem> {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val out = mutableListOf<TransferItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val state = try {
                    TransferState.valueOf(o.getString("state"))
                } catch (_: Exception) {
                    TransferState.QUEUED
                }
                out.add(
                    TransferItem(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        path = o.getString("path"),
                        // 旧版本没持久化 share，缺失时按 null 处理（doRead 会报「缺少共享名」，
                        // 而不是静默连到错误的共享上）。
                        share = o.optString("share").ifBlank { null },
                        relativePath = o.getString("relativePath"),
                        size = o.getLong("size"),
                        bytes = o.getLong("bytes"),
                        state = state,
                        error = o.getString("error").ifBlank { null }
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}

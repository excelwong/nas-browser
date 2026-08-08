package com.example.nasbrowser.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Persists the NAS connection settings. The password is stored with Android's
 * EncryptedSharedPreferences (AndroidKeyStore-backed AES) so it is not kept in plaintext.
 * Falls back to a plain SharedPreferences if the keystore is unavailable on the device.
 */
object PrefsStore {
    private const val FILE = "nas_secure_prefs"

    data class ConnectionPrefs(
        val host: String,
        val user: String,
        val pass: String,
        val domain: String,
        val remember: Boolean
    )

    fun load(context: Context): ConnectionPrefs {
        val sp = getSp(context)
        val remember = sp.getBoolean("remember", true)
        return ConnectionPrefs(
            host = sp.getString("host", "") ?: "",
            user = sp.getString("user", "") ?: "",
            pass = if (remember) (sp.getString("pass", "") ?: "") else "",
            domain = sp.getString("domain", "") ?: "",
            remember = remember
        )
    }

    fun save(context: Context, p: ConnectionPrefs) {
        val sp = getSp(context)
        sp.edit().apply {
            putString("host", p.host)
            putString("user", p.user)
            putString("domain", p.domain)
            putBoolean("remember", p.remember)
            if (p.remember) putString("pass", p.pass) else remove("pass")
            apply()
        }
    }

    /** Remove all saved connection settings (e.g. before switching to a different NAS). */
    fun clear(context: Context) {
        getSp(context).edit().clear().apply()
    }

    /** UI preference: the list sort mode (see [com.example.nasbrowser.model.SortMode]). */
    private const val UI_FILE = "nas_ui_prefs"

    fun loadSortMode(context: Context): String =
        context.getSharedPreferences(UI_FILE, Context.MODE_PRIVATE)
            .getString("sortMode", "NAME_ASC") ?: "NAME_ASC"

    fun saveSortMode(context: Context, mode: String) {
        context.getSharedPreferences(UI_FILE, Context.MODE_PRIVATE).edit()
            .putString("sortMode", mode).apply()
    }

    private fun getSp(context: Context): SharedPreferences {
        return try {
            val mainKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE,
                mainKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }
}

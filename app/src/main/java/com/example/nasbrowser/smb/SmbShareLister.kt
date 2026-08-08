package com.example.nasbrowser.smb

import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import java.util.Properties

/**
 * Enumerates the shares exposed by an SMB server — the "root directory" of a NAS, equivalent to
 * typing `\\host` in Windows Explorer.
 *
 * smbj 0.13.0 cannot list shares, so we use **jcifs-ng**, which performs the standard MS-RPC
 * `NetShareEnum` over the IPC$ named pipe. This is used ONLY for the root listing; all file
 * browsing and transfers continue to use smbj (SMB3) for better performance and dialect support.
 *
 * If enumeration fails (e.g. the server blocks RPC or only offers SMB3 to jcifs-ng's SMB2 client),
 * the caller falls back to letting the user type a share name manually.
 */
object SmbShareLister {
    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val props = Properties()
                    // Prefer SMB2 (and SMB3 where the server allows it); disable legacy SMB1.
                    props["jcifs.smb.client.enableSMB2"] = "true"
                    props["jcifs.smb.client.enableSMB1"] = "false"
                    SingletonContext.init(props)
                    initialized = true
                }
            }
        }
    }

    fun listShares(host: String, user: String, pass: String, domain: String): List<String> {
        ensureInit()
        val ctx = SingletonContext.getInstance()
        val auth = NtlmPasswordAuthentication(ctx, domain.ifBlank { "" }, user, pass)
        val authed = ctx.withCredentials(auth)
        val root = SmbFile("smb://$host/", authed)
        return root.listFiles().mapNotNull { f ->
            val name = f.name.trimEnd('/')
            if (name.isNotBlank()) name else null
        }
    }
}

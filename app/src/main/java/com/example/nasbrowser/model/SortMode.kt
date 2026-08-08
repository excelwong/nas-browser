package com.example.nasbrowser.model

/**
 * How the file/folder list is sorted in the browser. Directories are always kept at the top
 * (handled by [com.example.nasbrowser.smb.SmbClientManager]); this selects the secondary key.
 */
enum class SortMode(val key: String, val label: String) {
    /** File name, A → Z. */
    NAME_ASC("NAME_ASC", "文件名 (A→Z)"),
    /** File name, Z → A. */
    NAME_DESC("NAME_DESC", "文件名 (Z→A)"),
    /** Last-modified time, oldest → newest. */
    DATE_ASC("DATE_ASC", "修改时间 (旧→新)"),
    /** Last-modified time, newest → oldest. */
    DATE_DESC("DATE_DESC", "修改时间 (新→旧)");

    companion object {
        fun fromKey(k: String?): SortMode = values().firstOrNull { it.key == k } ?: NAME_ASC
    }
}

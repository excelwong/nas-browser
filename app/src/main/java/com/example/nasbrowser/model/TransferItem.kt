package com.example.nasbrowser.model

enum class TransferState { QUEUED, ACTIVE, PAUSED, FAILED, DONE }

data class TransferItem(
    val id: String,
    val name: String,
    val path: String,
    val share: String? = null,
    val relativePath: String,
    val size: Long,
    val bytes: Long,
    val state: TransferState,
    val error: String? = null
)

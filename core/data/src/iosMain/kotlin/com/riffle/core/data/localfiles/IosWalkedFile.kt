package com.riffle.core.data.localfiles

data class IosWalkedFile(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
)

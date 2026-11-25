package com.riffle.core.data.localfiles

import okio.Source

interface FolderWalker {
    suspend fun walk(treeUri: String): List<WalkedFile>
}

class WalkedFile(
    val originalUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
    val openSource: suspend () -> Source,
)

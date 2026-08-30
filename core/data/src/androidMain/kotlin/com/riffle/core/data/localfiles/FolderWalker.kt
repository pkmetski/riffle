package com.riffle.core.data.localfiles

/**
 * Walks a SAF tree URI (or, in tests, an in-memory fake filesystem) and yields every candidate
 * file. Recursion depth is unbounded but callers filter by extension before opening bytes.
 *
 * Implementations are cheap to construct and stateless per call.
 */
interface FolderWalker {

    /**
     * @param treeUri the persistable SAF tree URI, or an arbitrary string the test walker understands.
     * @return a sequence-like list of walked files, root-first, in a stable order.
     */
    suspend fun walk(treeUri: String): List<WalkedFile>
}

data class WalkedFile(
    val originalUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
    /**
     * A callback to open the file's bytes. Called at most twice per file (once to sniff the
     * head, once to copy). Real impls stream from `ContentResolver.openInputStream(uri)`.
     */
    val openStream: suspend () -> java.io.InputStream,
)

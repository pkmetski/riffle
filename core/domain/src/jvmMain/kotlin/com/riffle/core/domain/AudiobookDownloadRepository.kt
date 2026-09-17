package com.riffle.core.domain

/**
 * JVM extension of [AudiobookDownloadRepository] that overrides [localSession]. The default
 * implementation in the common interface returns null; this extension provides a real impl.
 * Kept for backward compatibility with Android callers that inject the specific subtype.
 */
interface JvmAudiobookDownloadRepository : AudiobookDownloadRepository {
    override fun localSession(sourceId: String, itemId: String): AudiobookSession?
}

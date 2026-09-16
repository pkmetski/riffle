package com.riffle.core.domain

/**
 * JVM extension of [AudiobookCacheRepository] that overrides [localSession] and [awaitCachedAudiobook].
 * The default implementations in the common interface are no-ops; this extension provides real impls.
 * Kept for backward compatibility with Android callers that inject the specific subtype.
 */
interface JvmAudiobookCacheRepository : AudiobookCacheRepository {
    override fun localSession(sourceId: String, itemId: String): AudiobookSession?
    override suspend fun awaitCachedAudiobook(sourceId: String, itemId: String, session: AudiobookSession)
}

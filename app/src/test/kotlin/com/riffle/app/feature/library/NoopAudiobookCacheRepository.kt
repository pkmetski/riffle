package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookSession

/** Test double: nothing is ever cached. */
internal object NoopAudiobookCacheRepository : AudiobookCacheRepository {
    override fun isCached(sourceId: String, itemId: String): Boolean = false
    override fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
    override suspend fun awaitCachedAudiobook(sourceId: String, itemId: String, session: AudiobookSession) = Unit
    override suspend fun remove(sourceId: String, itemId: String): Long = 0L
}

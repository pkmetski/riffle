package com.riffle.core.data

import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.AudioPlaybackPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import javax.inject.Inject

/**
 * Persists per-book audio playback settings keyed by the resolved [AudioIdentity] (ADR 0028). Unlike
 * the formatting store, the identity already carries (serverId, bookId), so no active-server lookup
 * is needed. Saving the default removes the row, keeping "no row == default".
 */
class AudioPlaybackPreferencesStoreImpl @Inject constructor(
    private val dao: AudioPlaybackPreferencesDao,
) : AudioPlaybackPreferencesStore {

    override suspend fun load(identity: AudioIdentity): Float? =
        dao.get(identity.serverId, identity.bookId)?.speed

    override suspend fun save(identity: AudioIdentity, speed: Float) {
        if (speed == DEFAULT_PLAYBACK_SPEED) {
            dao.delete(identity.serverId, identity.bookId)
            return
        }
        dao.upsert(AudioPlaybackPreferencesEntity(identity.serverId, identity.bookId, speed))
    }

    override suspend fun clear(identity: AudioIdentity) {
        dao.delete(identity.serverId, identity.bookId)
    }

    override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {
        if (old == new) return
        val existing = dao.get(old.serverId, old.bookId) ?: return
        dao.delete(old.serverId, old.bookId)
        dao.upsert(existing.copy(serverId = new.serverId, bookId = new.bookId))
    }
}

package com.riffle.core.data

import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import javax.inject.Inject

/**
 * Persists per-book audio playback settings keyed by the resolved [AudioIdentity] (ADR 0028). Unlike
 * the formatting store, the identity already carries (serverId, bookId), so no active-server lookup
 * is needed.
 *
 * A row means "this book has a user-chosen speed"; its absence means "follow the global default speed"
 * (the player resolves the fallback). The two are now distinct — the global default is configurable and
 * may be anything — so [save] persists the chosen value verbatim and never deletes: deleting on a
 * hardcoded 1.0× would silently discard a deliberate "play this one book at normal speed" choice and
 * snap it back to the global default. Use [clear] to genuinely un-customise a book.
 */
class AudioPlaybackPreferencesStoreImpl @Inject constructor(
    private val dao: AudioPlaybackPreferencesDao,
) : AudioPlaybackPreferencesStore {

    override suspend fun load(identity: AudioIdentity): Float? =
        dao.get(identity.serverId, identity.bookId)?.speed

    override suspend fun save(identity: AudioIdentity, speed: Float) {
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

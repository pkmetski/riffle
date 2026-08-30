package com.riffle.core.data

import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.models.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlaybackPreferencesStoreImplTest {

    private val id = AudioIdentity("s1", "42")

    @Test
    fun `save then load returns the speed`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 1.5f)
        assertEquals(1.5f, store.load(id))
    }

    @Test
    fun `load is null when nothing saved`() = runTest {
        assertNull(AudioPlaybackPreferencesStoreImpl(FakeDao()).load(id))
    }

    @Test
    fun `saving 1x persists an explicit per-book choice and does not delete the row`() = runTest {
        // 1.0x is a deliberate "play THIS book at normal speed" choice that must survive even when the
        // global default speed is faster — deleting the row would snap the book back to that global
        // default on reopen. (Pre-global-default this deleted the row under "no row == 1.0x default".)
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 1.5f)
        store.save(id, DEFAULT_PLAYBACK_SPEED)
        assertEquals(DEFAULT_PLAYBACK_SPEED, store.load(id))
    }

    @Test
    fun `clear removes the record`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 2f)
        store.clear(id)
        assertNull(store.load(id))
    }

    @Test
    fun `rekey moves the saved speed to the new identity`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 1.25f)
        val newId = AudioIdentity("s2", "audio")
        store.rekey(id, newId)
        assertNull(store.load(id))
        assertEquals(1.25f, store.load(newId))
    }

    @Test
    fun `rekey is a no-op when there is nothing at the old identity`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.rekey(id, AudioIdentity("s2", "audio"))
        assertNull(store.load(AudioIdentity("s2", "audio")))
    }

    private class FakeDao : AudioPlaybackPreferencesDao {
        private val rows = mutableMapOf<Pair<String, String>, AudioPlaybackPreferencesEntity>()
        override suspend fun upsert(entity: AudioPlaybackPreferencesEntity) {
            rows[entity.sourceId to entity.bookId] = entity
        }
        override suspend fun get(sourceId: String, bookId: String) = rows[sourceId to bookId]
        override suspend fun delete(sourceId: String, bookId: String) { rows.remove(sourceId to bookId) }
    }
}

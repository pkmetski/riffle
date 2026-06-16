package com.riffle.app.feature.settings

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.AudioPlaybackPreferencesStoreImpl
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AudioIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level regression for per-book speed persistence (Bug 1).
 *
 * Before the fix in EpubReaderViewModel.setSpeed(), the debounce job called
 * save(AudioIdentity("", bookId)) because audioSettingsIdentity was not yet
 * resolved when the job ran. A subsequent load(AudioIdentity("srv-1", bookId))
 * found nothing and fell back to the global default — the bug.
 *
 * These tests use a real Room database on-device to verify:
 *   - save → load with the same correct identity succeeds
 *   - save with empty serverId (old bug) is invisible to load with real serverId
 */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackSpeedPersistenceTest {

    private lateinit var db: RiffleDatabase
    private lateinit var store: AudioPlaybackPreferencesStoreImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            db.serverDao().upsert(
                ServerEntity("srv-1", "http://abs", isActive = true, insecureConnectionAllowed = false, username = "test")
            )
        }
        store = AudioPlaybackPreferencesStoreImpl(db.audioPlaybackPreferencesDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun savedSpeedIsLoadedBackWithSameIdentity() = runBlocking {
        val identity = AudioIdentity("srv-1", "book-123")
        store.save(identity, 1.5f)
        assertEquals(1.5f, store.load(identity))
    }

    @Test
    fun loadReturnsNullWhenNothingSaved() = runBlocking {
        assertNull(store.load(AudioIdentity("srv-1", "book-missing")))
    }

    /**
     * Directly reproduces Bug 1: before the fix, EpubReaderViewModel saved with
     * AudioIdentity("", bookId). This test proves that such a save is not visible
     * when loading with the real server identity — confirming why speed was lost.
     */
    @Test
    fun saveWithEmptyServerIdIsNotVisibleToRealIdentityLoad() = runBlocking {
        val buggyIdentity = AudioIdentity("", "book-123")
        val realIdentity  = AudioIdentity("srv-1", "book-123")

        runCatching { store.save(buggyIdentity, 1.75f) }

        assertNull(
            "Speed saved with empty serverId must not be returned for the real server identity — " +
                "if this fails the pre-fix bug would have been invisible",
            store.load(realIdentity),
        )
    }

    @Test
    fun updatedSpeedOverwritesPreviousRow() = runBlocking {
        val identity = AudioIdentity("srv-1", "book-upd")
        store.save(identity, 1.25f)
        store.save(identity, 2.0f)
        assertEquals(2.0f, store.load(identity))
    }

    @Test
    fun savingSpeed1fIsHonoredAndNotDeleted() = runBlocking {
        // A deliberate 1.0x per-book choice must persist so it survives reopen even when the global
        // default speed is faster — it must NOT be discarded as if it were the absence of a choice.
        val identity = AudioIdentity("srv-1", "book-del")
        store.save(identity, 1.5f)
        store.save(identity, 1.0f)
        assertEquals(1.0f, store.load(identity))
    }
}

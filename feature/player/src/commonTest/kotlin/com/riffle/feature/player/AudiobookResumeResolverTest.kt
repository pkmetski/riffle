package com.riffle.feature.player

import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.common.Clock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudiobookResumeResolverTest {

    private class FakeClock(val nowMs: Long) : Clock {
        override fun nowMs(): Long = nowMs
        override fun nowNs(): Long = nowMs * 1_000_000L
    }

    private class FakePositionStore(
        var loadedSec: Double? = null,
        var loadedTs: Long = 0L,
    ) : AudiobookPositionStore {
        val saves = mutableListOf<Triple<String, String, Double>>()
        val timestampUpdates = mutableListOf<Triple<String, String, Long>>()
        override suspend fun save(sourceId: String, itemId: String, payload: Double) {
            saves += Triple(sourceId, itemId, payload)
        }
        override suspend fun load(sourceId: String, itemId: String): Double? = loadedSec
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = loadedTs
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) { }
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) { }
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
            timestampUpdates += Triple(sourceId, itemId, millis)
        }
    }

    private fun session(
        duration: Double = 1000.0,
        serverCurrentTimeSec: Double = 0.0,
        serverLastUpdate: Long = 0L,
    ) = AudiobookSession(
        trackUrls = listOf("http://x/t"),
        tracks = listOf(AudiobookTrackSpan(0, 0.0, duration)),
        timeline = AudiobookTimeline(duration),
        serverCurrentTimeSec = serverCurrentTimeSec,
        serverLastUpdate = serverLastUpdate,
    )

    @Test
    fun pullRemoteWins_savesLocallyAndReturnsRemotePosition() = runTest {
        val store = FakePositionStore(loadedSec = 100.0, loadedTs = 1_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(500.0, result.resumeSec)
        assertEquals(5_000L, result.resumeStamp)
        assertEquals(1, store.saves.size)
        assertEquals(500.0, store.saves[0].third)
    }

    @Test
    fun pushLocalWins_noPersist_returnsLocal() = runTest {
        val store = FakePositionStore(loadedSec = 900.0, loadedTs = 10_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 100.0, serverLastUpdate = 1_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(900.0, result.resumeSec)
        assertEquals(10_000L, result.resumeStamp)
        assertTrue(store.saves.isEmpty(), "push-local does not write back")
    }

    @Test
    fun noLocalNoServer_positiveReadingProgress_fallsBackToFraction() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 400.0, serverCurrentTimeSec = 0.0, serverLastUpdate = 0L),
            readingProgressFraction = 0.5f,
            startAtSec = -1.0,
        )

        assertEquals(200.0, result.resumeSec)
        assertEquals(0L, result.resumeStamp)
    }

    @Test
    fun finishedBook_resume_rewindsToZeroOnNormalOpen() = runTest {
        val store = FakePositionStore(loadedSec = 999.5, loadedTs = 5_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 1000.0, serverCurrentTimeSec = 999.5, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(0.0, result.resumeSec)
    }

    @Test
    fun finishedBook_setsWasFinishedOnOpen() = runTest {
        val store = FakePositionStore(loadedSec = 999.5, loadedTs = 5_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 1000.0, serverCurrentTimeSec = 999.5, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertTrue(result.wasFinishedOnOpen, "back-stack restore of finished book must not auto-play")
    }

    @Test
    fun inProgressBook_doesNotSetWasFinishedOnOpen() = runTest {
        val store = FakePositionStore(loadedSec = 500.0, loadedTs = 5_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 1000.0, serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(false, result.wasFinishedOnOpen)
    }

    @Test
    fun handoffToFinishedPosition_doesNotSetWasFinishedOnOpen() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(1L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 1000.0),
            readingProgressFraction = 0f,
            startAtSec = 999.5,
        )

        assertEquals(false, result.wasFinishedOnOpen)
    }

    @Test
    fun handoffOverride_overridesReconciler_stampsAndPersists() = runTest {
        val store = FakePositionStore(loadedSec = 100.0, loadedTs = 1_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(9_999L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = 250.0,
        )

        assertEquals(250.0, result.resumeSec)
        assertEquals(9_999L, result.resumeStamp)
        assertEquals(250.0, store.saves.last().third, "handoff persists position")
        assertEquals(9_999L, store.timestampUpdates.last().third, "handoff persists fresh timestamp")
    }

    @Test
    fun handoff_coercesAboveDurationToDuration() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(1L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 400.0),
            readingProgressFraction = 0f,
            startAtSec = 999.0,
        )

        assertEquals(400.0, result.resumeSec)
    }

    @Test
    fun finishedBookGuard_firesAtExactBoundary() = runTest {
        val store = FakePositionStore(loadedSec = 999.0, loadedTs = 5_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 1000.0, serverCurrentTimeSec = 999.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(0.0, result.resumeSec)
        assertTrue(result.wasFinishedOnOpen, "inclusive boundary must set wasFinishedOnOpen")
    }

    @Test
    fun zeroedSession_returnsZeroResumeWithNoCrash() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "chit-1",
            itemId = "prikazki/some-tale",
            session = session(duration = 0.0, serverCurrentTimeSec = 0.0, serverLastUpdate = 0L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(0.0, result.resumeSec)
        assertEquals(0L, result.resumeStamp)
        assertTrue(store.saves.isEmpty(), "zeroed session must not write back to store")
        assertEquals(false, result.wasFinishedOnOpen, "unknown duration cannot be finished")
    }

    @Test
    fun emptySourceId_noStoreIO_defaultsFromSession() = runTest {
        val store = FakePositionStore(loadedSec = 999.0, loadedTs = 99_999L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "",
            itemId = "book",
            session = session(serverCurrentTimeSec = 30.0, serverLastUpdate = 42L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(30.0, result.resumeSec)
        assertTrue(store.saves.isEmpty())
        assertTrue(store.timestampUpdates.isEmpty())
    }
}

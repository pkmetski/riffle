package com.riffle.app.feature.audiobook

import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `pull-remote wins → saves locally + returns remote position`() = runTest {
        val store = FakePositionStore(loadedSec = 100.0, loadedTs = 1_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(500.0, result.resumeSec, 0.0001)
        assertEquals(5_000L, result.resumeStamp)
        assertEquals(1, store.saves.size)
        assertEquals(500.0, store.saves[0].third, 0.0001)
    }

    @Test
    fun `push-local wins → no persist, returns local`() = runTest {
        val store = FakePositionStore(loadedSec = 900.0, loadedTs = 10_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 100.0, serverLastUpdate = 1_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(900.0, result.resumeSec, 0.0001)
        assertEquals(10_000L, result.resumeStamp)
        assertTrue("push-local does not write back", store.saves.isEmpty())
    }

    @Test
    fun `no local, no server, positive readingProgress → falls back to progress fraction`() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 400.0, serverCurrentTimeSec = 0.0, serverLastUpdate = 0L),
            readingProgressFraction = 0.5f,
            startAtSec = -1.0,
        )

        assertEquals(200.0, result.resumeSec, 0.01)
        assertEquals("progress-fallback returns zero stamp (inbound-only)", 0L, result.resumeStamp)
    }

    @Test
    fun `finished-book resume rewinds to 0 on normal open`() = runTest {
        val store = FakePositionStore(loadedSec = 999.5, loadedTs = 5_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 1000.0, serverCurrentTimeSec = 999.5, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(0.0, result.resumeSec, 0.0001)
    }

    @Test
    fun `handoff override overrides reconciler, stamps fresh, persists`() = runTest {
        val store = FakePositionStore(loadedSec = 100.0, loadedTs = 1_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(9_999L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = 250.0,
        )

        assertEquals(250.0, result.resumeSec, 0.0001)
        assertEquals(9_999L, result.resumeStamp)
        assertEquals("handoff persists position", 250.0, store.saves.last().third, 0.0001)
        assertEquals("handoff persists fresh timestamp", 9_999L, store.timestampUpdates.last().third)
    }

    @Test
    fun `handoff coerces above duration to duration`() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(1L))

        val result = resolver.resolve(
            sourceId = "srv",
            itemId = "book",
            session = session(duration = 400.0),
            readingProgressFraction = 0f,
            startAtSec = 999.0,
        )

        assertEquals(400.0, result.resumeSec, 0.0001)
    }

    @Test
    fun `zeroed session (Chitanka fresh open) → resumeSec=0, resumeStamp=0, no crash`() = runTest {
        // Chitanka's openAudiobook returns a stream with serverCurrentTimeSec=0, serverLastUpdate=0,
        // totalDurationSec=0 (unknown until ExoPlayer resolves each track). A fresh open has no local
        // row either. Pin: the resolver returns (0.0, 0L) — the reconciler picks InSync (0 !> 0),
        // audiobookResumeSec short-circuits on durationSec<=0 (no bogus fraction*0 fallback), and
        // audiobookStartSec no-ops on durationSec<=0 (no finished-book rewind on a zero-duration).
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "chit-1",
            itemId = "prikazki/some-tale",
            session = session(duration = 0.0, serverCurrentTimeSec = 0.0, serverLastUpdate = 0L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(0.0, result.resumeSec, 0.0001)
        assertEquals(0L, result.resumeStamp)
        assertTrue("zeroed session must not write back to store", store.saves.isEmpty())
    }

    @Test
    fun `empty sourceId → no store IO, defaults from session`() = runTest {
        val store = FakePositionStore(loadedSec = 999.0, loadedTs = 99_999L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            sourceId = "",
            itemId = "book",
            session = session(serverCurrentTimeSec = 30.0, serverLastUpdate = 42L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        // reconciler input: localSec=null (skipped), remoteSec=30.0, remoteUpdatedAt=42 → PullRemote to 30
        assertEquals(30.0, result.resumeSec, 0.0001)
        assertTrue(store.saves.isEmpty())
        assertTrue(store.timestampUpdates.isEmpty())
    }
}

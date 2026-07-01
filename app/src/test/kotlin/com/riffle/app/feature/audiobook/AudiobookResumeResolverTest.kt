package com.riffle.app.feature.audiobook

import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override suspend fun save(serverId: String, itemId: String, payload: Double) {
            saves += Triple(serverId, itemId, payload)
        }
        override suspend fun load(serverId: String, itemId: String): Double? = loadedSec
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = loadedTs
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
            timestampUpdates += Triple(serverId, itemId, millis)
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
            serverId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(500.0, result.resumeSec, 0.0001)
        assertEquals(5_000L, result.resumeStamp)
        assertTrue(result.hadTrackedPosition)
        assertEquals(1, store.saves.size)
        assertEquals(500.0, store.saves[0].third, 0.0001)
    }

    @Test
    fun `push-local wins → no persist, returns local`() = runTest {
        val store = FakePositionStore(loadedSec = 900.0, loadedTs = 10_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            serverId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 100.0, serverLastUpdate = 1_000L),
            readingProgressFraction = 0f,
            startAtSec = -1.0,
        )

        assertEquals(900.0, result.resumeSec, 0.0001)
        assertEquals(10_000L, result.resumeStamp)
        assertTrue(result.hadTrackedPosition)
        assertTrue("push-local does not write back", store.saves.isEmpty())
    }

    @Test
    fun `no local, no server, positive readingProgress → falls back to progress fraction`() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            serverId = "srv",
            itemId = "book",
            session = session(duration = 400.0, serverCurrentTimeSec = 0.0, serverLastUpdate = 0L),
            readingProgressFraction = 0.5f,
            startAtSec = -1.0,
        )

        assertEquals(200.0, result.resumeSec, 0.01)
        assertEquals(0L, result.resumeStamp, )
        assertFalse("progress-fallback is inbound-only", result.hadTrackedPosition)
    }

    @Test
    fun `finished-book resume rewinds to 0 on normal open`() = runTest {
        val store = FakePositionStore(loadedSec = 999.5, loadedTs = 5_000L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            serverId = "srv",
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
            serverId = "srv",
            itemId = "book",
            session = session(serverCurrentTimeSec = 500.0, serverLastUpdate = 5_000L),
            readingProgressFraction = 0f,
            startAtSec = 250.0,
        )

        assertEquals(250.0, result.resumeSec, 0.0001)
        assertEquals(9_999L, result.resumeStamp)
        assertTrue(result.hadTrackedPosition)
        assertEquals("handoff persists position", 250.0, store.saves.last().third, 0.0001)
        assertEquals("handoff persists fresh timestamp", 9_999L, store.timestampUpdates.last().third)
    }

    @Test
    fun `handoff coerces above duration to duration`() = runTest {
        val store = FakePositionStore(loadedSec = null, loadedTs = 0L)
        val resolver = AudiobookResumeResolver(store, FakeClock(1L))

        val result = resolver.resolve(
            serverId = "srv",
            itemId = "book",
            session = session(duration = 400.0),
            readingProgressFraction = 0f,
            startAtSec = 999.0,
        )

        assertEquals(400.0, result.resumeSec, 0.0001)
    }

    @Test
    fun `empty serverId → no store IO, defaults from session`() = runTest {
        val store = FakePositionStore(loadedSec = 999.0, loadedTs = 99_999L)
        val resolver = AudiobookResumeResolver(store, FakeClock(0L))

        val result = resolver.resolve(
            serverId = "",
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

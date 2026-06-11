package com.riffle.core.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSyncCycleTest {

    /** A fake remote with injectable GET result, recorded PATCH, and the timestamp the PATCH stores. */
    private class FakeRemote(
        override val id: String,
        private val getResult: RemoteRead?,
        private val patchStamp: Long = 1L,
    ) : SyncRemote {
        var patchedWith: CanonicalReaderPosition? = null
            private set
        var patchFails: Boolean = false
        var getCalls = 0
            private set

        override suspend fun tryGet(): RemoteRead? {
            getCalls++
            return getResult
        }

        override suspend fun tryPatch(canonical: CanonicalReaderPosition): Long? {
            if (patchFails) return null
            patchedWith = canonical
            return patchStamp
        }
    }

    private fun pos(v: String) = CanonicalReaderPosition(v)

    @Test
    fun `the newest remote wins, the reader jumps to it, and stale remotes are patched to it`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 100)
        val ebook = FakeRemote("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90))
        val audio = FakeRemote("ABS_AUDIO", RemoteRead(pos("B"), lastUpdate = 80))
        val storyteller = FakeRemote("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(ebook, audio, storyteller))

        // One inbound winner, one jump, to the Storyteller position.
        assertEquals(pos("S"), result.jumpTo)
        assertEquals(150, result.canonicalLastUpdate)
        // Stale remotes are patched to the single canonical winner; the winner is not.
        assertEquals(pos("S"), ebook.patchedWith)
        assertEquals(pos("S"), audio.patchedWith)
        assertNull(storyteller.patchedWith)
        assertEquals(setOf("ABS_EBOOK", "ABS_AUDIO"), result.patched)
    }

    @Test
    fun `when local is newest there is no jump and every remote is patched to local`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 200)
        val ebook = FakeRemote("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90))
        val storyteller = FakeRemote("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(ebook, storyteller))

        assertNull(result.jumpTo)
        assertEquals(pos("L"), ebook.patchedWith)
        assertEquals(pos("L"), storyteller.patchedWith)
        assertEquals(setOf("ABS_EBOOK", "STORYTELLER"), result.patched)
    }

    @Test
    fun `a write the server stamps later than the winner is adopted as the canonical lastUpdate`() = runTest {
        // ABS stamps an ebook write with a server time later than the local clock. The cycle must
        // adopt it, so the write doesn't read back next cycle as a newer remote and bounce the reader.
        val local = LocalCanonical(pos("L"), lastUpdate = 200)
        val ebook = FakeRemote("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90), patchStamp = 5_000)

        val result = CanonicalSyncCycle.run(local, listOf(ebook))

        assertNull("local won, so no jump", result.jumpTo)
        assertEquals(pos("L"), ebook.patchedWith)
        assertEquals("the server write timestamp is adopted", 5_000L, result.canonicalLastUpdate)
    }

    @Test
    fun `a failed GET excludes that remote from comparison and from patching`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 100)
        val ebook = FakeRemote("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90))
        val audio = FakeRemote("ABS_AUDIO", getResult = null) // unreachable
        val storyteller = FakeRemote("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(ebook, audio, storyteller))

        assertEquals(pos("S"), result.jumpTo)
        // The unreachable remote is neither compared nor patched (we don't know its state).
        assertNull(audio.patchedWith)
        assertTrue("ABS_AUDIO" !in result.patched)
    }

    @Test
    fun `a failed PATCH leaves that remote out of the patched set but does not poison others`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 200)
        val ebook = FakeRemote("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90)).apply { patchFails = true }
        val storyteller = FakeRemote("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(ebook, storyteller))

        assertEquals(setOf("STORYTELLER"), result.patched)
        assertEquals(pos("L"), storyteller.patchedWith)
    }
}

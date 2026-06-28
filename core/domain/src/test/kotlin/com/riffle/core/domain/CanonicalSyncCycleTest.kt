package com.riffle.core.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSyncCycleTest {

    /** A fake peer with injectable GET result, recorded PATCH, and the timestamp the PATCH stores. */
    private class FakePeer(
        override val id: String,
        private val getResult: RemoteRead?,
        private val patchStamp: Long = 1L,
    ) : ProgressPeer {
        var patchedWith: CanonicalReaderPosition? = null
            private set
        var patchFails: Boolean = false
        var getCalls = 0
            private set

        override suspend fun tryGet(): RemoteRead? {
            getCalls++
            return getResult
        }

        override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult {
            if (patchFails) return WriteResult.Failed("test-induced failure")
            patchedWith = canonical
            return WriteResult.Ok(patchStamp)
        }
    }

    private fun pos(v: String) = CanonicalReaderPosition(v)

    @Test
    fun `the newest remote wins, the reader jumps to it, and stale remotes are patched to it`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 100)
        val ebook = FakePeer("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90))
        val audio = FakePeer("ABS_AUDIO", RemoteRead(pos("B"), lastUpdate = 80))
        val storyteller = FakePeer("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

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
        val ebook = FakePeer("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90))
        val storyteller = FakePeer("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

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
        val ebook = FakePeer("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90), patchStamp = 5_000)

        val result = CanonicalSyncCycle.run(local, listOf(ebook))

        assertNull("local won, so no jump", result.jumpTo)
        assertEquals(pos("L"), ebook.patchedWith)
        assertEquals("the server write timestamp is adopted", 5_000L, result.canonicalLastUpdate)
    }

    @Test
    fun `a failed GET excludes that remote from comparison and from patching`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 100)
        val ebook = FakePeer("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90))
        val audio = FakePeer("ABS_AUDIO", getResult = null) // unreachable
        val storyteller = FakePeer("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(ebook, audio, storyteller))

        assertEquals(pos("S"), result.jumpTo)
        // The unreachable remote is neither compared nor patched (we don't know its state).
        assertNull(audio.patchedWith)
        assertTrue("ABS_AUDIO" !in result.patched)
    }

    @Test
    fun `a failed PATCH leaves that remote out of the patched set but does not poison others`() = runTest {
        val local = LocalCanonical(pos("L"), lastUpdate = 200)
        val ebook = FakePeer("ABS_EBOOK", RemoteRead(pos("A"), lastUpdate = 90)).apply { patchFails = true }
        val storyteller = FakePeer("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(ebook, storyteller))

        assertEquals(setOf("STORYTELLER"), result.patched)
        assertEquals(pos("L"), storyteller.patchedWith)
    }

    @Test
    fun `a peer that skips its PATCH is treated identically to one that fails`() = runTest {
        // WriteResult.Skipped models a deliberate no-op (e.g. translator couldn't place the
        // canonical position, or an inbound-only wrapper suppressed the outbound). The cycle must
        // leave it out of `patched` and never adopt a stamp, exactly like Failed.
        val local = LocalCanonical(pos("L"), lastUpdate = 200)
        val skipping = object : ProgressPeer {
            override val id = "SKIPPING"
            override suspend fun tryGet() = RemoteRead(pos("X"), lastUpdate = 90)
            override suspend fun tryPatch(canonical: CanonicalReaderPosition) = WriteResult.Skipped
        }
        val storyteller = FakePeer("STORYTELLER", RemoteRead(pos("S"), lastUpdate = 150))

        val result = CanonicalSyncCycle.run(local, listOf(skipping, storyteller))

        assertEquals(setOf("STORYTELLER"), result.patched)
        assertEquals("the skipped peer's (non-existent) stamp must not be adopted", 200L, result.canonicalLastUpdate)
    }
}

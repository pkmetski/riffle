package com.riffle.core.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSyncStrategyTest {

    private class FakeRemote(override val id: String) : SyncRemote {
        var patchedWith: CanonicalReaderPosition? = null
        override suspend fun tryGet(): RemoteRead? = null
        override suspend fun tryPatch(canonical: CanonicalReaderPosition): Boolean {
            patchedWith = canonical
            return true
        }
    }

    private val local = LocalCanonical(CanonicalReaderPosition("L"), lastUpdate = 100)

    @Test
    fun `a three-peer state reconciles over all three remotes`() = runTest {
        val built = mutableListOf<RemoteKind>()
        val strategy = ProgressSyncStrategy { kind -> built += kind; FakeRemote(kind.name) }

        val state = BookSyncState(
            isMatched = true,
            confirmedAbsLinkCount = 1,
            prerequisitesCached = true,
            openedSide = OpenedSide.ABS,
        )
        strategy.runCycle(state, local)

        assertEquals(
            setOf(RemoteKind.ABS_EBOOK, RemoteKind.ABS_AUDIO, RemoteKind.STORYTELLER),
            built.toSet(),
        )
    }

    @Test
    fun `the multi-link guard never constructs or reconciles the Storyteller remote`() = runTest {
        val built = mutableListOf<RemoteKind>()
        val strategy = ProgressSyncStrategy { kind -> built += kind; FakeRemote(kind.name) }

        val state = BookSyncState(
            isMatched = true,
            confirmedAbsLinkCount = 2, // collision → Storyteller excluded
            prerequisitesCached = true,
            openedSide = OpenedSide.READALOUD,
        )
        strategy.runCycle(state, local)

        assertTrue(RemoteKind.STORYTELLER !in built)
        assertEquals(setOf(RemoteKind.ABS_EBOOK, RemoteKind.ABS_AUDIO), built.toSet())
    }

    @Test
    fun `an unmatched ABS-side book runs single-peer over ABS ebook only`() = runTest {
        val built = mutableListOf<RemoteKind>()
        val strategy = ProgressSyncStrategy { kind -> built += kind; FakeRemote(kind.name) }

        val state = BookSyncState(false, 0, prerequisitesCached = false, openedSide = OpenedSide.ABS)
        strategy.runCycle(state, local)

        assertEquals(listOf(RemoteKind.ABS_EBOOK), built)
    }

    @Test
    fun `a remote the factory cannot build is skipped without poisoning the cycle`() = runTest {
        // Prerequisites cached but the Storyteller remote can't be constructed (e.g. bundle gone).
        val strategy = ProgressSyncStrategy { kind ->
            if (kind == RemoteKind.STORYTELLER) null else FakeRemote(kind.name)
        }

        val state = BookSyncState(true, 1, prerequisitesCached = true, openedSide = OpenedSide.ABS)
        val result = strategy.runCycle(state, local)

        // No remote was newer than local and none can be read, so no jump; cycle still completes.
        assertNull(result.jumpTo)
    }
}

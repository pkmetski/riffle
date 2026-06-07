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
    fun `a matched state reconciles the ebook and Storyteller — never the audiobook`() = runTest {
        val built = mutableListOf<RemoteKind>()
        val strategy = ProgressSyncStrategy { kind -> built += kind; FakeRemote(kind.name) }

        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )
        strategy.runCycle(state, local)

        // The audiobook is push-only; it is never built as a reconciled remote.
        assertEquals(
            setOf(RemoteKind.ABS_EBOOK, RemoteKind.STORYTELLER),
            built.toSet(),
        )
    }

    @Test
    fun `an ebook-only match reconciles the ebook and Storyteller`() = runTest {
        val built = mutableListOf<RemoteKind>()
        val strategy = ProgressSyncStrategy { kind -> built += kind; FakeRemote(kind.name) }

        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = false, // no matched audio item
            prerequisitesCached = true,
        )
        strategy.runCycle(state, local)

        assertEquals(setOf(RemoteKind.ABS_EBOOK, RemoteKind.STORYTELLER), built.toSet())
    }

    @Test
    fun `an unmatched ABS-side book runs single-peer over ABS ebook only`() = runTest {
        val built = mutableListOf<RemoteKind>()
        val strategy = ProgressSyncStrategy { kind -> built += kind; FakeRemote(kind.name) }

        val state = BookSyncState(isMatched = false, hasAbsEbookTarget = true, hasAbsAudioTarget = false, prerequisitesCached = false)
        strategy.runCycle(state, local)

        assertEquals(listOf(RemoteKind.ABS_EBOOK), built)
    }

    @Test
    fun `a remote the factory cannot build is skipped without poisoning the cycle`() = runTest {
        // Prerequisites cached but the Storyteller remote can't be constructed (e.g. bundle gone).
        val strategy = ProgressSyncStrategy { kind ->
            if (kind == RemoteKind.STORYTELLER) null else FakeRemote(kind.name)
        }

        val state = BookSyncState(isMatched = true, hasAbsEbookTarget = true, hasAbsAudioTarget = true, prerequisitesCached = true)
        val result = strategy.runCycle(state, local)

        // No remote was newer than local and none can be read, so no jump; cycle still completes.
        assertNull(result.jumpTo)
    }
}

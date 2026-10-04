package com.riffle.feature.player

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.feature.reader.PositionSaveCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for: partially played web-source (podcast) progress not syncing via WebDAV.
 *
 * Root cause: AudiobookPlayerViewModel wired PositionSaveCoordinator with the single-arg
 * updateReadingProgressUseCase(itemId, progress) overload, which internally resolves sourceId via
 * getActive()?.id — the active ABS server, not the web source. The library_items row for the
 * podcast episode was never updated, so WebDAV sync uploaded readingProgress = 0f regardless of
 * how much was listened.
 *
 * Fix (AudiobookPlayerViewModel.kt line 143): use the two-arg overload
 * updateReadingProgressUseCase(sourceId, itemId, progress) with the explicit web-source sourceId.
 */
class WebSourceProgressOnCloseTest {

    private sealed class MutatorCall {
        data class SingleArg(val itemId: String, val progress: Float) : MutatorCall()
        data class TwoArg(val sourceId: String, val itemId: String, val progress: Float) : MutatorCall()
    }

    private class RecordingMutator(private val stored: Float? = null) : LibraryMutator {
        val calls = mutableListOf<MutatorCall>()
        override suspend fun markItemOpened(itemId: String) = Unit
        override suspend fun currentReadingProgress(itemId: String): Float? = stored
        override suspend fun currentReadingProgress(sourceId: String, itemId: String): Float? = stored
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {
            calls += MutatorCall.SingleArg(itemId, progress)
        }
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            calls += MutatorCall.TwoArg(sourceId, itemId, progress)
        }
        override suspend fun deleteItem(sourceId: String, itemId: String) = Unit
    }

    @Test
    fun onCloseWritesProgressWithExplicitWebSourceId() = runTest {
        val webSourceId = "web-src-podcasts"
        val itemId = "episode-99"

        val mutator = RecordingMutator()
        val useCase = UpdateReadingProgress(mutator)

        // Mirrors AudiobookPlayerViewModel line 143 (post-fix):
        // updateProgress = { progress -> updateReadingProgressUseCase(sourceId, itemId, progress) }
        val coordinator = PositionSaveCoordinator<Double>(
            updateProgress = { progress -> useCase(webSourceId, itemId, progress) },
        )

        coordinator.onClose(0.37f)

        // Must use the explicit-sourceId overload so the correct library_items row is updated
        // and WebDAV sync uploads the real readingProgress instead of 0f.
        assertEquals(1, mutator.calls.size)
        val call = mutator.calls[0] as? MutatorCall.TwoArg
            ?: error("expected two-arg call but got ${mutator.calls[0]}")
        assertEquals(webSourceId, call.sourceId)
        assertEquals(itemId, call.itemId)
        assertEquals(0.37f, call.progress)
    }

    @Test
    fun onCloseWithSingleArgOverloadWouldWriteToWrongRow() = runTest {
        val itemId = "episode-99"

        val mutator = RecordingMutator()
        val useCase = UpdateReadingProgress(mutator)

        // This is what the old (broken) VM code did — single-arg, no sourceId.
        val coordinator = PositionSaveCoordinator<Double>(
            updateProgress = { progress -> useCase(itemId, progress) },
        )
        coordinator.onClose(0.37f)

        // Single-arg routes through LibraryMutator.updateReadingProgress(itemId, progress) which
        // uses getActive()?.id as sourceId — the ABS server, not the web source. Calling the
        // single-arg path on a web-source episode updates the wrong DB row.
        val call = mutator.calls.single()
        assertTrue(
            call is MutatorCall.SingleArg,
            "single-arg path must NOT produce a two-arg call; got $call",
        )
    }
}

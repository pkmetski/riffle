package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class UpdateReadingProgressTest {

    private data class ProgressCall(val sourceId: String?, val itemId: String, val progress: Float)

    private class RecordingMutator(
        private val storedProgress: Float? = null,
    ) : LibraryMutator {
        val calls = mutableListOf<ProgressCall>()
        override suspend fun markItemOpened(itemId: String) = Unit
        override suspend fun currentReadingProgress(itemId: String): Float? = storedProgress
        override suspend fun currentReadingProgress(sourceId: String, itemId: String): Float? = storedProgress
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {
            calls += ProgressCall(sourceId = null, itemId = itemId, progress = progress)
        }
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            calls += ProgressCall(sourceId = sourceId, itemId = itemId, progress = progress)
        }
        override suspend fun deleteItem(sourceId: String, itemId: String) = Unit
    }

    @Test
    fun `single-arg invoke delegates to LibraryMutator without sourceId`() = runTest {
        val mutator = RecordingMutator()
        val useCase = UpdateReadingProgress(mutator)

        useCase("item-1", 0.42f)

        assertEquals(1, mutator.calls.size)
        assertEquals(ProgressCall(sourceId = null, itemId = "item-1", progress = 0.42f), mutator.calls[0])
    }

    @Test
    fun `two-arg invoke delegates to LibraryMutator with explicit sourceId`() = runTest {
        val mutator = RecordingMutator()
        val useCase = UpdateReadingProgress(mutator)

        useCase("source-oreilly", "item-1", 0.17f)

        assertEquals(1, mutator.calls.size)
        val call = mutator.calls[0]
        // Regression: before the fix the sourceId overload did not exist; calling with a non-active
        // sourceId silently wrote progress under the active source, making two copies of the same
        // book share their progress bar in the Riffle home screen.
        assertEquals(call.sourceId, "source-oreilly")
        assertEquals(call.itemId, "item-1")
        assertEquals(0.17f, call.progress)
    }

    @Test
    fun `single-arg keeps a finished book finished when the reader closes on the cover`() = runTest {
        // Regression: opening a marked-as-read book and closing it on the cover dropped it to 0%.
        val mutator = RecordingMutator(storedProgress = 1.0f)
        val useCase = UpdateReadingProgress(mutator)

        useCase("item-1", 0.004f)

        assertTrue(mutator.calls.isEmpty(), "a cover-only close must not overwrite readingProgress of a finished book")
    }

    @Test
    fun `single-arg un-finishes a book once it is read past the cover`() = runTest {
        // Regression: reading chapter 6 of a marked-as-read book left both progress bars at 100%.
        val mutator = RecordingMutator(storedProgress = 1.0f)
        val useCase = UpdateReadingProgress(mutator)

        useCase("item-1", 0.4f)

        assertEquals(listOf(ProgressCall(sourceId = null, itemId = "item-1", progress = 0.4f)), mutator.calls)
    }
    @Test
    fun `two-arg keeps a finished book finished on a cover-only close but tracks real reading`() = runTest {
        val mutator = RecordingMutator(storedProgress = 1.0f)
        val useCase = UpdateReadingProgress(mutator)

        useCase("src-1", "item-1", 0.0f)
        assertTrue(mutator.calls.isEmpty())

        useCase("src-1", "item-1", 0.28f)
        assertEquals(listOf(ProgressCall(sourceId = "src-1", itemId = "item-1", progress = 0.28f)), mutator.calls)
    }
    @Test
    fun `single-arg allows updating a non-finished book`() = runTest {
        val mutator = RecordingMutator(storedProgress = 0.5f)
        val useCase = UpdateReadingProgress(mutator)

        useCase("item-1", 0.75f)

        assertEquals(1, mutator.calls.size, "updateReadingProgress must be called for a non-finished book")
    }

    @Test
    fun `two-arg and single-arg routes are independent — non-active sourceId does not bleed`() = runTest {
        val mutator = RecordingMutator()
        val useCase = UpdateReadingProgress(mutator)

        // Active-source book uses single-arg path.
        useCase("item-abs", 0.50f)
        // Non-active-source book uses two-arg path with its own sourceId.
        useCase("source-oreilly", "item-oreilly", 0.20f)

        assertEquals(2, mutator.calls.size)
        // First call: active-source path (no explicit sourceId)
        assertEquals(ProgressCall(sourceId = null, itemId = "item-abs", progress = 0.50f), mutator.calls[0])
        // Second call: explicit sourceId — never confused with the first
        assertEquals(ProgressCall(sourceId = "source-oreilly", itemId = "item-oreilly", progress = 0.20f), mutator.calls[1])
    }
}

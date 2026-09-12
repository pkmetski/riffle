package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateReadingProgressTest {

    private data class ProgressCall(val sourceId: String?, val itemId: String, val progress: Float)

    private class RecordingMutator : LibraryMutator {
        val calls = mutableListOf<ProgressCall>()
        override suspend fun markItemOpened(itemId: String) = Unit
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
        assertEquals("source-oreilly", call.sourceId)
        assertEquals("item-1", call.itemId)
        assertEquals(0.17f, call.progress)
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

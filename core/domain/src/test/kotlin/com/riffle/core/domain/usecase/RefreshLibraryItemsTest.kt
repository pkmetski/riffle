package com.riffle.core.domain.usecase

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: the matcher/syncer fan-out (originally inside `LibraryRepositoryImpl.refreshLibraryItems`)
 * is the entire reason this use-case exists. The dispatch is on an application-scope background
 * launch so the caller returns the moment the Room write lands. We also gate it on the refresh
 * succeeding — a network failure shouldn't trigger work against stale state.
 */
class RefreshLibraryItemsTest {

    private class FixedRefresher(private val result: LibraryRefreshResult) : LibraryRefresher {
        override suspend fun refreshLibraries() = result
        override suspend fun refreshLibraryItems(libraryId: String) = result
        override suspend fun refreshSeries(libraryId: String) = result
        override suspend fun refreshCollections(libraryId: String) = result
    }

    private class RecordingSyncer : StorytellerReadaloudCacheSyncer {
        val done = CompletableDeferred<Unit>()
        override suspend fun syncStale() { done.complete(Unit) }
    }

    private class RecordingReconciler : ReadaloudLinkReconciler {
        val done = CompletableDeferred<Unit>()
        override suspend fun reconcileLinks() { done.complete(Unit) }
    }

    private class ImmediateScope : ApplicationScope {
        override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
            coroutineScope.launch(block = block)
        override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
            coroutineScope.async(block = block).await()
    }

    @Test
    fun `fires syncStale and reconcileLinks on success`() = runTest {
        val syncer = RecordingSyncer()
        val reconciler = RecordingReconciler()
        val useCase = RefreshLibraryItems(
            FixedRefresher(LibraryRefreshResult.Success), syncer, reconciler, ImmediateScope(),
        )

        val result = useCase("lib-1")

        assertEquals(LibraryRefreshResult.Success, result)
        // Dispatchers.Unconfined runs the launch inline; both finish before invoke returns.
        assertTrue(syncer.done.isCompleted)
        assertTrue(reconciler.done.isCompleted)
    }

    @Test
    fun `does not fan out on network error`() = runTest {
        val syncer = RecordingSyncer()
        val reconciler = RecordingReconciler()
        val error = LibraryRefreshResult.NetworkError(RuntimeException("boom"))
        val useCase = RefreshLibraryItems(FixedRefresher(error), syncer, reconciler, ImmediateScope())

        val result = useCase("lib-1")

        assertEquals(error, result)
        assertTrue(!syncer.done.isCompleted)
        assertTrue(!reconciler.done.isCompleted)
    }

    @Test
    fun `does not fan out on NoActiveServer`() = runTest {
        val syncer = RecordingSyncer()
        val reconciler = RecordingReconciler()
        val useCase = RefreshLibraryItems(
            FixedRefresher(LibraryRefreshResult.NoActiveServer), syncer, reconciler, ImmediateScope(),
        )

        useCase("lib-1")

        assertTrue(!syncer.done.isCompleted)
        assertTrue(!reconciler.done.isCompleted)
    }
}

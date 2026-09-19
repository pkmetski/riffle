package com.riffle.feature.library

import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moved from app/src/test (issue #1065) together with the manager itself: BookImportManagerImpl is
 * now commonMain, so these three scenarios run on iOS as well as JVM. The two snackbar-message
 * tests stayed behind in app — they assert an Android string resource.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookImportManagerImplTest {

    @Test
    fun `import remains observable while the originating screen is gone`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = BookImportManagerImpl(CoroutineScope(dispatcher))
        val gate = CompletableDeferred<Unit>()

        manager.start("import:item") { onProgress, _ ->
            onProgress(CatalogImportProgress(CatalogImportPhase.Uploading))
            gate.await()
            CatalogImportResult.Uploaded(destinationItemId = "abs-item")
        }
        testScheduler.advanceUntilIdle()

        assertEquals(
            BookImportState.InProgress(CatalogImportPhase.Uploading),
            manager.states.value["import:item"],
        )

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(BookImportState.Completed, manager.states.value["import:item"])
    }

    @Test
    fun `accepted upload is completed before background reconciliation completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = BookImportManagerImpl(CoroutineScope(dispatcher))
        val gate = CompletableDeferred<Unit>()

        manager.start("import:item") { onProgress, _ ->
            onProgress(CatalogImportProgress(CatalogImportPhase.Uploaded))
            gate.await()
            CatalogImportResult.Uploaded()
        }
        testScheduler.runCurrent()

        assertTrue(manager.states.value["import:item"] === BookImportState.Completed)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(BookImportState.Completed, manager.states.value["import:item"])
    }

    @Test
    fun `accepted upload remains completed while reconciliation continues`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = BookImportManagerImpl(CoroutineScope(dispatcher))
        val gate = CompletableDeferred<Unit>()

        manager.start("import:item") { onProgress, _ ->
            onProgress(CatalogImportProgress(CatalogImportPhase.Uploaded))
            onProgress(CatalogImportProgress(CatalogImportPhase.Reconciling))
            gate.await()
            CatalogImportResult.Uploaded()
        }
        testScheduler.runCurrent()

        assertTrue(manager.states.value["import:item"] === BookImportState.Completed)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(BookImportState.Completed, manager.states.value["import:item"])
    }
}

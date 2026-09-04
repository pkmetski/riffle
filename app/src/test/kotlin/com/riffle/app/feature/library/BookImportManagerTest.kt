package com.riffle.app.feature.library

import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookImportManagerTest {

    @Test
    fun `completed import produces a success message`() {
        assertEquals(
            com.riffle.app.R.string.ui_upload_completed,
            bookImportSnackbarMessage(BookImportState.Completed),
        )
    }

    @Test
    fun `non-completed import does not produce a success message`() {
        assertEquals(
            null,
            bookImportSnackbarMessage(BookImportState.InProgress(CatalogImportPhase.Uploading)),
        )
    }

    @Test
    fun `import remains observable while the originating screen is gone`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = BookImportManager(CoroutineScope(dispatcher))
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
        val manager = BookImportManager(CoroutineScope(dispatcher))
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
        val manager = BookImportManager(CoroutineScope(dispatcher))
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

package com.riffle.core.data

import com.riffle.core.domain.RemoteKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the [ReconcileLocks] singleton (#321). The progress and annotation axes use
 * separate mutex maps, so locks on the same `(server, item)` across axes must not contend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileLocksTest {

    @Test
    fun `withAnnotationLock serializes concurrent calls for the same book`() = runTest {
        val locks = ReconcileLocks()
        val firstEntered = CompletableDeferred<Unit>()
        val firstHold = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = async {
            locks.withAnnotationLock("srv", "item") {
                firstEntered.complete(Unit)
                firstHold.await()
            }
        }
        firstEntered.await()

        val second = async {
            locks.withAnnotationLock("srv", "item") {
                secondEntered = true
            }
        }
        // Yield as much as possible — second must NOT enter while first is held.
        advanceUntilIdle()
        assertFalse("second waiter must not enter while first holds the lock", secondEntered)

        firstHold.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
    }

    @Test
    fun `withAnnotationLock does not contend across different books`() = runTest {
        val locks = ReconcileLocks()
        val firstHold = CompletableDeferred<Unit>()
        var secondCompleted = false

        val first = async {
            locks.withAnnotationLock("srv", "book-A") { firstHold.await() }
        }
        val second = async {
            locks.withAnnotationLock("srv", "book-B") { secondCompleted = true }
        }
        advanceUntilIdle()
        assertTrue("different books must not contend", secondCompleted)

        firstHold.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `annotation lock does not contend with progress lock on the same book`() = runTest {
        // Annotation and progress use separate mutex maps so an open-book progress reconcile
        // never blocks an annotation push for the same (server, item).
        val locks = ReconcileLocks()
        val progressHold = CompletableDeferred<Unit>()
        var annotationRan = false

        val progress = async {
            locks.withLock("srv", "item", RemoteKind.ABS_EBOOK) { progressHold.await() }
        }
        val annotation = async {
            locks.withAnnotationLock("srv", "item") { annotationRan = true }
        }
        advanceUntilIdle()
        assertTrue("progress and annotation axes must be independent", annotationRan)

        progressHold.complete(Unit)
        progress.await()
        annotation.await()
    }

    @Test
    fun `withLock serializes concurrent progress calls for the same kind`() = runTest {
        // Regression: existing progress behaviour preserved under the rename.
        val locks = ReconcileLocks()
        val firstHold = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = async {
            locks.withLock("srv", "item", RemoteKind.ABS_EBOOK) { firstHold.await() }
        }
        val second = async {
            locks.withLock("srv", "item", RemoteKind.ABS_EBOOK) { secondEntered = true }
        }
        advanceUntilIdle()
        assertFalse(secondEntered)

        firstHold.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
    }

    @Test
    fun `withLock does not contend across kinds`() = runTest {
        val locks = ReconcileLocks()
        val ebookHold = CompletableDeferred<Unit>()
        var audioRan = false

        val ebook = async {
            locks.withLock("srv", "item", RemoteKind.ABS_EBOOK) { ebookHold.await() }
        }
        val audio = async {
            locks.withLock("srv", "item", RemoteKind.ABS_AUDIO) { audioRan = true }
        }
        advanceUntilIdle()
        assertTrue("different kinds must not contend on the same book", audioRan)

        ebookHold.complete(Unit)
        ebook.await()
        audio.await()
    }

    @Test
    fun `lock map reuses the same mutex across calls for the same key`() = runTest {
        // Two sequential acquisitions of the same key must both succeed (the mutex is reused
        // and properly released, not recreated each call).
        val locks = ReconcileLocks()
        var calls = 0
        locks.withAnnotationLock("srv", "item") { calls++ }
        locks.withAnnotationLock("srv", "item") { calls++ }
        assertEquals(2, calls)
    }
}

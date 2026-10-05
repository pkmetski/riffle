package com.riffle.app.sync

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exception-handling policy of [PlaylistSyncWorker].
 *
 * [com.riffle.core.data.PlaylistSweep] absorbs all transport failures (network, server, auth)
 * internally, so any exception that escapes [PlaylistSyncWorker.doWork] is a programming error.
 * The worker must return [ListenableWorker.Result.failure] — not retry — so WorkManager does not
 * schedule an exponential-backoff retry for a condition that retrying cannot fix.
 *
 * This contrasts with [AnnotationSyncWorker], whose [outcomeToResult] maps transient network/server
 * failures to [ListenableWorker.Result.retry] because [com.riffle.core.data.AnnotationSweep] *does*
 * surface those failures as [com.riffle.core.sync.CycleOutcome] to the caller.
 */
class PlaylistSyncWorkerExceptionPolicyTest {

    @Test
    fun `programming error maps to failure not retry`() {
        assertEquals(
            ListenableWorker.Result.failure(),
            sweepExceptionToResult(RuntimeException("unexpected")),
        )
    }

    @Test
    fun `IOException maps to failure not retry`() {
        assertEquals(
            ListenableWorker.Result.failure(),
            sweepExceptionToResult(java.io.IOException("transport")),
        )
    }
}

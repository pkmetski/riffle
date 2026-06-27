package com.riffle.app.sync

import androidx.work.ListenableWorker
import com.riffle.core.data.CycleOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: when [com.riffle.core.data.AnnotationSweep] reports a transient transport failure
 * (network/server/unknown), the worker must return [ListenableWorker.Result.retry] so WorkManager
 * schedules an exponential-backoff retry. That retry job carries the CONNECTED constraint, which
 * is what re-fires sync the moment the device comes back online. The previous shape returned
 * `success()` unconditionally, which left the in-memory status badge saying "will retry
 * automatically" while no retry was actually queued — sync would only resume on the next 1-hour
 * periodic tick or a foreground connectivity-regain kick (and the kick uses KEEP, so it can't
 * unblock anything either).
 */
class AnnotationSyncWorkerOutcomeMappingTest {

    @Test
    fun `unconfigured target maps to success`() {
        assertEquals(ListenableWorker.Result.success(), outcomeToResult(null))
    }

    @Test
    fun `success maps to success`() {
        assertEquals(
            ListenableWorker.Result.success(),
            outcomeToResult(CycleOutcome.Success(atMs = 1L)),
        )
    }

    @Test
    fun `network failure maps to retry`() {
        assertEquals(
            ListenableWorker.Result.retry(),
            outcomeToResult(CycleOutcome.Failed.Network(atMs = 1L, message = "eof")),
        )
    }

    @Test
    fun `server failure maps to retry`() {
        assertEquals(
            ListenableWorker.Result.retry(),
            outcomeToResult(CycleOutcome.Failed.Server(atMs = 1L, code = 503)),
        )
    }

    @Test
    fun `unknown failure maps to retry`() {
        assertEquals(
            ListenableWorker.Result.retry(),
            outcomeToResult(CycleOutcome.Failed.Unknown(atMs = 1L, message = "boom")),
        )
    }

    @Test
    fun `auth failure maps to permanent failure`() {
        assertEquals(
            ListenableWorker.Result.failure(),
            outcomeToResult(CycleOutcome.Failed.Auth(atMs = 1L, code = 401)),
        )
    }

    @Test
    fun `tls failure maps to permanent failure`() {
        assertEquals(
            ListenableWorker.Result.failure(),
            outcomeToResult(CycleOutcome.Failed.Tls(atMs = 1L, message = "bad cert")),
        )
    }
}

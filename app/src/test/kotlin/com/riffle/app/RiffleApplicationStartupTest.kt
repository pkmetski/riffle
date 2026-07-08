package com.riffle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the guard in [RiffleApplication.onCreate] that prevents main-process startup work — which
 * touches WorkManager via `ProgressSyncScheduler.sweepNow` — from running in ACRA's private
 * `:acra` reporter process. Without the guard the reporter crash-loops with
 * `WorkManager is not initialized properly` because WorkManager's ContentProvider auto-initializer
 * is scoped to the main process.
 */
class RiffleApplicationStartupTest {

    @Test
    fun `skips main-process startup in ACRA reporter process`() {
        assertTrue(shouldSkipMainProcessStartup(isAcraProcess = true))
    }

    @Test
    fun `runs main-process startup in the main process`() {
        assertFalse(shouldSkipMainProcessStartup(isAcraProcess = false))
    }
}

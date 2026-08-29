package com.riffle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication

class RiffleApplicationTest {

    @Test
    fun `riffleKoinModules produce a loadable Koin graph`() {
        // Boots a Koin application with the exact module list RiffleApplication passes to
        // startKoin. Fails on any definition-level error (duplicate binding, invalid module),
        // which is the only validation Koin offers at startup after Hilt's compile-time
        // graph checking is gone.
        val koinApp = koinApplication { modules(riffleKoinModules()) }
        try {
            koinApp.createEagerInstances()
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun `shouldSkipMainProcessStartup returns true for ACRA process`() {
        assertTrue(shouldSkipMainProcessStartup(isAcraProcess = true))
    }

    @Test
    fun `shouldSkipMainProcessStartup returns false for main process`() {
        assertFalse(shouldSkipMainProcessStartup(isAcraProcess = false))
    }
}

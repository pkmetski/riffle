package com.riffle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiffleApplicationTest {

    @Test
    fun `shouldSkipMainProcessStartup returns true for ACRA process`() {
        assertTrue(shouldSkipMainProcessStartup(isAcraProcess = true))
    }

    @Test
    fun `shouldSkipMainProcessStartup returns false for main process`() {
        assertFalse(shouldSkipMainProcessStartup(isAcraProcess = false))
    }
}

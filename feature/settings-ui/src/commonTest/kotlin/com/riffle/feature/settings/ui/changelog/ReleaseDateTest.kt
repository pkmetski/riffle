package com.riffle.feature.settings.ui.changelog

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseDateTest {
    @Test
    fun releaseDateLabelParsesIsoTimestamp() {
        val label = releaseDateLabel("2024-03-15T10:00:00Z")
        assertNotNull(label)
        assertTrue(label.contains("2024"), "Expected year 2024 in '$label'")
    }

    @Test
    fun releaseDateLabelReturnsNullForBlank() {
        assertNull(releaseDateLabel(""))
        assertNull(releaseDateLabel("   "))
    }

    @Test
    fun releaseDateLabelReturnsNullForInvalidInput() {
        assertNull(releaseDateLabel("not-a-date"))
    }
}

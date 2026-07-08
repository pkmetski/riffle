package com.riffle.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportShareSubjectTest {
    @Test
    fun `subject wraps the timestamp in the Riffle crash report label`() {
        assertEquals(
            "Riffle crash report (Jan 1, 2026, 12:34:00 PM)",
            crashReportShareSubject("Jan 1, 2026, 12:34:00 PM"),
        )
    }
}

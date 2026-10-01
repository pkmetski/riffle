package com.riffle.feature.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CrashReportShareSubjectTest {
    @Test
    fun subjectWrapsTheTimestampInTheRiffleCrashReportLabel() {
        assertEquals(
            "Riffle crash report (Jan 1, 2026, 12:34:00 PM)",
            crashReportShareSubject("Jan 1, 2026, 12:34:00 PM"),
        )
    }
}

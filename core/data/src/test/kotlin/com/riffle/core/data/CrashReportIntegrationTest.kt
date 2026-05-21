package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration test: verifies the full write→read path through real filesystem I/O.
 * Simulates what FileCrashReportSender writes so that CrashReportRepositoryImpl can read it back.
 */
class CrashReportIntegrationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `crash report written to app-private storage is read back correctly by repository`() {
        val reportFile = tmpFolder.newFile("crash_report.txt")
        val simulatedContent = buildString {
            appendLine("STACK_TRACE:")
            appendLine("java.lang.NullPointerException: null")
            appendLine("\tat com.riffle.app.MainActivity.onCreate(MainActivity.kt:42)")
            appendLine()
            appendLine("PHONE_MODEL: Pixel 7")
            appendLine("ANDROID_VERSION: 14")
            appendLine("APP_VERSION: 0.1.0")
            appendLine("AVAILABLE_MEMORY: 512MB")
        }

        // Simulate what FileCrashReportSender writes
        reportFile.writeText(simulatedContent)
        val timestampAfterWrite = reportFile.lastModified()

        val repo = CrashReportRepositoryImpl(reportFile)
        val report = repo.getLastCrashReport()

        assertNotNull("repository must return a report", report)
        assertEquals(simulatedContent, report!!.content)
        assertEquals(timestampAfterWrite, report.timestampMillis)
        assertTrue("NullPointerException" in report.content)
        assertTrue("Pixel 7" in report.content)
    }
}

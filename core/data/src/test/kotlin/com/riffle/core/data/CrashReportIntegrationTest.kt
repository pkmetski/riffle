package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration test: verifies the full write→read path through real filesystem I/O.
 * Simulates what FileCrashReportSender writes (one .txt per crash) so that
 * CrashReportRepositoryImpl can read them back in order.
 */
class CrashReportIntegrationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `multiple crash reports survive and are returned newest first`() {
        val dir = tmpFolder.newFolder("crash_reports")
        val first = dir.resolve("100-aaaaaaaa.txt").apply {
            writeText("STACK_TRACE:\njava.lang.NullPointerException: first\n")
        }
        first.setLastModified(1000L)
        val second = dir.resolve("200-bbbbbbbb.txt").apply {
            writeText("STACK_TRACE:\njava.lang.IllegalStateException: second\n")
        }
        second.setLastModified(2000L)

        val reports = CrashReportRepositoryImpl(dir).listCrashReports()

        assertEquals(2, reports.size)
        assertTrue("second" in reports[0].content)
        assertTrue("first" in reports[1].content)
        assertEquals(2000L, reports[0].timestampMillis)
        assertEquals(1000L, reports[1].timestampMillis)
    }
}

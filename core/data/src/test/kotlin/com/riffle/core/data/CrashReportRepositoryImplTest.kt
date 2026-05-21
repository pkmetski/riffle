package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportRepositoryImplTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // Cycle 3: returns CrashReport when the file exists
    @Test
    fun `getLastCrashReport returns report with content and file timestamp when file exists`() {
        val file = tmpFolder.newFile("crash_report.txt")
        val expectedContent = "STACK_TRACE:\njava.lang.RuntimeException: test\n"
        file.writeText(expectedContent)

        val repo = CrashReportRepositoryImpl(file)
        val report = repo.getLastCrashReport()

        assertEquals(expectedContent, report?.content)
        assertEquals(file.lastModified(), report?.timestampMillis)
    }

    // Cycle 4: returns null when no file exists
    @Test
    fun `getLastCrashReport returns null when file does not exist`() {
        val file = tmpFolder.root.resolve("no_such_file.txt")

        val repo = CrashReportRepositoryImpl(file)

        assertNull(repo.getLastCrashReport())
    }
}

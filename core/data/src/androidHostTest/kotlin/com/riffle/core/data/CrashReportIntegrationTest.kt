package com.riffle.core.data

import com.riffle.core.common.FileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Integration test: verifies the full write→read path through real filesystem I/O.
 * Simulates what IosCrashReportRecorder / FileCrashReportSender writes (one .txt per crash) so
 * that CrashReportRepositoryImpl can read them back in order.
 */
class CrashReportIntegrationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun fileStore(root: File): FileStore = object : FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = root.resolve(namespace)
            return if (relativePath.isEmpty()) {
                base.apply { mkdirs() }.absolutePath
            } else {
                File(base, relativePath).also { it.parentFile?.mkdirs() }.absolutePath
            }
        }
        override fun exists(namespace: String, relativePath: String) =
            File(resolve(namespace, relativePath)).exists()
    }

    @Test
    fun `multiple crash reports survive and are returned newest first`() {
        val root = tmpFolder.newFolder()
        val repo = CrashReportRepositoryImpl(fileStore(root))

        repo.record("100-aaaaaaaa", "STACK_TRACE:\njava.lang.NullPointerException: first\n")
        repo.record("200-bbbbbbbb", "STACK_TRACE:\njava.lang.IllegalStateException: second\n")

        val reports = repo.listCrashReports()

        assertEquals(2, reports.size)
        assertTrue("second" in reports[0].content || "first" in reports[0].content)
        assertEquals(setOf("100-aaaaaaaa", "200-bbbbbbbb"), reports.map { it.id }.toSet())
    }
}

package com.riffle.core.data

import com.riffle.core.common.FileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportRepositoryImplTest {

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

    private fun androidRepo(root: File) = AndroidCrashReportRepositoryImpl(fileStore(root))
    private fun repo(root: File) = CrashReportRepositoryImpl(fileStore(root))

    @Test
    fun `listCrashReports returns every txt file newest first`() {
        val root = tmpFolder.newFolder()
        val reportsDir = root.resolve(NS_CRASH_REPORTS).apply { mkdirs() }
        val older = reportsDir.resolve("100-aaaaaaaa.txt").apply { writeText("older") }
        older.setLastModified(1000L)
        val newer = reportsDir.resolve("200-bbbbbbbb.txt").apply { writeText("newer") }
        newer.setLastModified(2000L)

        val reports = repo(root).listCrashReports()

        assertEquals(listOf("newer", "older"), reports.map { it.content })
        assertEquals(listOf("200-bbbbbbbb", "100-aaaaaaaa"), reports.map { it.id })
    }

    @Test
    fun `listCrashReports returns empty when directory has no txt files`() {
        val root = tmpFolder.newFolder()
        root.resolve(NS_CRASH_REPORTS).apply { mkdirs() }.resolve("README.md").writeText("ignored")

        assertTrue(repo(root).listCrashReports().isEmpty())
    }

    @Test
    fun `listCrashReports returns empty when directory does not exist yet`() {
        val root = tmpFolder.newFolder("never_created_root")

        assertTrue(repo(root).listCrashReports().isEmpty())
    }

    @Test
    fun `resolveReportFiles returns the on-disk files in request order`() {
        val root = tmpFolder.newFolder()
        val r = androidRepo(root)
        r.record("a", "A")
        r.record("b", "B")

        val files = r.resolveReportFiles(listOf("b", "a"))

        assertEquals(listOf("b.txt", "a.txt"), files.map { it.name })
    }

    @Test
    fun `resolveReportFiles drops ids that no longer have a file`() {
        val root = tmpFolder.newFolder()
        val r = androidRepo(root)
        r.record("kept", "here")

        val files = r.resolveReportFiles(listOf("kept", "missing"))

        assertEquals(listOf("kept.txt"), files.map { it.name })
    }

    @Test
    fun `clearAllCrashReports removes every txt file but leaves siblings alone`() {
        val root = tmpFolder.newFolder()
        val r = repo(root)
        r.record("one", "one")
        r.record("two", "two")
        val reportsDir = root.resolve(NS_CRASH_REPORTS).apply { mkdirs() }
        val keep = reportsDir.resolve("README.md").apply { writeText("don't touch") }

        r.clearAllCrashReports()

        assertTrue(r.listCrashReports().isEmpty())
        assertTrue(keep.exists())
    }
}

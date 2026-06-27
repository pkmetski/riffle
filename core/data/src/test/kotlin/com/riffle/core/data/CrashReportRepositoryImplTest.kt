package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportRepositoryImplTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `listCrashReports returns every txt file newest first`() {
        val dir = tmpFolder.newFolder("crash_reports")
        val older = dir.resolve("100-aaaaaaaa.txt").apply { writeText("older") }
        older.setLastModified(1000L)
        val newer = dir.resolve("200-bbbbbbbb.txt").apply { writeText("newer") }
        newer.setLastModified(2000L)

        val reports = CrashReportRepositoryImpl(dir).listCrashReports()

        assertEquals(listOf("newer", "older"), reports.map { it.content })
        assertEquals(listOf("200-bbbbbbbb", "100-aaaaaaaa"), reports.map { it.id })
    }

    @Test
    fun `listCrashReports returns empty when directory has no txt files`() {
        val dir = tmpFolder.newFolder("crash_reports")
        // A non-txt sibling must be ignored — only ACRA-written reports are surfaced.
        dir.resolve("README.md").writeText("ignored")

        assertTrue(CrashReportRepositoryImpl(dir).listCrashReports().isEmpty())
    }

    @Test
    fun `listCrashReports returns empty when directory does not exist yet`() {
        val dir = tmpFolder.root.resolve("never_created")

        assertTrue(CrashReportRepositoryImpl(dir).listCrashReports().isEmpty())
    }

    @Test
    fun `resolveReportFiles returns the on-disk files in request order`() {
        val dir = tmpFolder.newFolder("crash_reports")
        dir.resolve("a.txt").writeText("A")
        dir.resolve("b.txt").writeText("B")
        val repo = CrashReportRepositoryImpl(dir)

        val files = repo.resolveReportFiles(listOf("b", "a"))

        assertEquals(listOf("b.txt", "a.txt"), files.map { it.name })
    }

    @Test
    fun `resolveReportFiles drops ids that no longer have a file`() {
        val dir = tmpFolder.newFolder("crash_reports")
        dir.resolve("kept.txt").writeText("here")
        val repo = CrashReportRepositoryImpl(dir)

        val files = repo.resolveReportFiles(listOf("kept", "missing"))

        assertEquals(listOf("kept.txt"), files.map { it.name })
    }

    @Test
    fun `clearAllCrashReports removes every txt file but leaves siblings alone`() {
        val dir = tmpFolder.newFolder("crash_reports")
        dir.resolve("one.txt").writeText("one")
        dir.resolve("two.txt").writeText("two")
        val keep = dir.resolve("README.md").apply { writeText("don't touch") }
        val repo = CrashReportRepositoryImpl(dir)

        repo.clearAllCrashReports()

        assertTrue(repo.listCrashReports().isEmpty())
        assertTrue(keep.exists())
    }
}

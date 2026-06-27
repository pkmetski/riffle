package com.riffle.app

import android.content.Context
import io.mockk.mockk
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCrashReportSenderTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun makeSender(dir: java.io.File = tmpFolder.newFolder("crash_reports")) =
        FileCrashReportSender(dir)

    private fun makeReport(
        stackTrace: String = "java.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.kt:10)",
        phoneModel: String = "Pixel 7",
        androidVersion: String = "14",
        appVersion: String = "0.1.0",
        availableMemory: String = "512MB",
    ): CrashReportData = CrashReportData().apply {
        put(ReportField.STACK_TRACE, stackTrace)
        put(ReportField.PHONE_MODEL, phoneModel)
        put(ReportField.ANDROID_VERSION, androidVersion)
        put(ReportField.APP_VERSION_NAME, appVersion)
        put(ReportField.AVAILABLE_MEM_SIZE, availableMemory)
    }

    @Test
    fun `buildContent produces non-empty string containing the stack trace`() {
        val content = makeSender().buildContent(makeReport(stackTrace = "java.lang.RuntimeException: boom"))

        assertTrue("content should not be empty", content.isNotBlank())
        assertTrue("content should contain the stack trace", "RuntimeException: boom" in content)
    }

    @Test
    fun `buildContent includes device model, OS version, app version, and memory`() {
        val content = makeSender().buildContent(makeReport(
            phoneModel = "Pixel 7",
            androidVersion = "14",
            appVersion = "0.1.0",
            availableMemory = "512MB",
        ))

        assertTrue("Pixel 7" in content)
        assertTrue("14" in content)
        assertTrue("0.1.0" in content)
        assertTrue("512MB" in content)
    }

    @Test
    fun `buildContent does not include server URL, user or book content fields`() {
        val report = makeReport()
        val content = makeSender().buildContent(report)

        assertFalse("CUSTOM_DATA must not appear", "CUSTOM_DATA" in content)
        assertFalse("SHARED_PREFERENCES must not appear", "SHARED_PREFERENCES" in content)
        assertFalse("LOGCAT must not appear", "LOGCAT" in content)
    }

    @Test
    fun `send writes a new txt file per crash so history is preserved`() {
        // The prior single-file design overwrote on every crash. Verify that two consecutive
        // sends with distinct stack traces leave two files behind — distinct content yields
        // distinct content-hashes, so the {epochMillis}-{hash} filename is unique without a
        // wall-clock delay between writes.
        val dir = tmpFolder.newFolder("crash_reports")
        val sender = makeSender(dir)

        sender.send(mockk<Context>(relaxed = true), makeReport(stackTrace = "first"))
        sender.send(mockk<Context>(relaxed = true), makeReport(stackTrace = "second"))

        val files = dir.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
        assertEquals(2, files.size)
        assertTrue("first" in files[0].readText() || "first" in files[1].readText())
        assertTrue("second" in files[0].readText() || "second" in files[1].readText())
    }

    @Test
    fun `send prunes the oldest report once max is exceeded`() {
        // Defense-in-depth: ACRA's LimiterConfiguration caps the upstream queue, but a
        // flapping crash inside one session would still write here. Cap the on-disk archive
        // so it never grows unbounded.
        val dir = tmpFolder.newFolder("crash_reports")
        // Pre-populate MAX_REPORTS old files so the next send must prune.
        repeat(FileCrashReportSender.MAX_REPORTS) { i ->
            dir.resolve("old-$i.txt").apply {
                writeText("old")
                setLastModified(1000L + i)
            }
        }
        val sender = FileCrashReportSender(dir)

        sender.send(mockk<Context>(relaxed = true),
            makeReport(stackTrace = "newest"))

        val files = dir.listFiles { f -> f.extension == "txt" }!!
        assertEquals(FileCrashReportSender.MAX_REPORTS, files.size)
        assertTrue("oldest pre-existing file should have been pruned",
            files.none { it.name == "old-0.txt" })
        assertTrue("newest crash must remain",
            files.any { "newest" in it.readText() })
    }
}

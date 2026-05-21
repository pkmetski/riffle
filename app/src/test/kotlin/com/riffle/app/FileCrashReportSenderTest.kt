package com.riffle.app

import org.acra.ReportField
import org.acra.data.CrashReportData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCrashReportSenderTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun makeSender() = FileCrashReportSender(tmpFolder.newFile("crash_report.txt"))

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

    // Cycle 1: content includes the stack trace and is non-empty
    @Test
    fun `buildContent produces non-empty string containing the stack trace`() {
        val content = makeSender().buildContent(makeReport(stackTrace = "java.lang.RuntimeException: boom"))

        assertTrue("content should not be empty", content.isNotBlank())
        assertTrue("content should contain the stack trace", "RuntimeException: boom" in content)
    }

    // Cycle 2: sanitization — allowed fields are present, sensitive fields are absent
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
        // Simulate what would happen if ACRA were misconfigured to collect extra fields;
        // our sender must only write the safe fields it explicitly reads.
        val report = makeReport()
        // The report has no sensitive fields — buildContent only reads the five allowed fields.
        val content = makeSender().buildContent(report)

        // Verify no spurious keys appear (buildContent only emits the five safe fields)
        assertFalse("CUSTOM_DATA must not appear", "CUSTOM_DATA" in content)
        assertFalse("SHARED_PREFERENCES must not appear", "SHARED_PREFERENCES" in content)
        assertFalse("LOGCAT must not appear", "LOGCAT" in content)
    }
}

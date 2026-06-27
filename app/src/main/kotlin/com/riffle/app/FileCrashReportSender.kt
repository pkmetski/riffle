package com.riffle.app

import android.content.Context
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File

/**
 * Persists each crash as its own file under [reportDir]. The prior single-file design
 * silently overwrote on every crash, so users couldn't see history — they only ever had
 * the latest. Filename is `{epochMillis}-{hash}.txt`: epoch sorts chronologically, the
 * hash disambiguates two crashes in the same millisecond. Caps retention at [MAX_REPORTS]
 * files; ACRA's LimiterConfiguration also bounds the upstream queue, but this is a
 * second guard so a flapping bug can't fill the disk between launches.
 */
class FileCrashReportSender(private val reportDir: File) : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        if (!reportDir.exists()) reportDir.mkdirs()
        val content = buildContent(errorContent)
        val name = "${System.currentTimeMillis()}-${"%08x".format(content.hashCode())}.txt"
        File(reportDir, name).writeText(content)
        pruneToMax()
    }

    private fun pruneToMax() {
        val files = reportDir.listFiles { f -> f.isFile && f.extension == "txt" } ?: return
        if (files.size <= MAX_REPORTS) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS)
            .forEach { it.delete() }
    }

    internal fun buildContent(report: CrashReportData): String = buildString {
        appendLine("STACK_TRACE:")
        appendLine(report.getString(ReportField.STACK_TRACE) ?: "")
        appendLine()
        appendLine("PHONE_MODEL: ${report.getString(ReportField.PHONE_MODEL) ?: ""}")
        appendLine("ANDROID_VERSION: ${report.getString(ReportField.ANDROID_VERSION) ?: ""}")
        appendLine("APP_VERSION: ${report.getString(ReportField.APP_VERSION_NAME) ?: ""}")
        appendLine("AVAILABLE_MEMORY: ${report.getString(ReportField.AVAILABLE_MEM_SIZE) ?: ""}")
    }

    companion object {
        const val MAX_REPORTS = 20
    }
}

class FileCrashReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        FileCrashReportSender(File(context.filesDir, "crash_reports"))
}

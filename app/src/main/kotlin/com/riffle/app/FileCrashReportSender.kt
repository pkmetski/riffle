package com.riffle.app

import android.content.Context
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File

class FileCrashReportSender(private val reportFile: File) : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        reportFile.writeText(buildContent(errorContent))
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
}

class FileCrashReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        FileCrashReportSender(File(context.filesDir, "crash_report.txt"))
}

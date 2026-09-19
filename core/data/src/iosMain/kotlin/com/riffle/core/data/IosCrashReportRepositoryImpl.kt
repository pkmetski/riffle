package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.models.CrashReport
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.timeIntervalSince1970

const val NS_CRASH_REPORTS = "crash-reports"

private const val CRASH_REPORT_EXTENSION = ".txt"

/**
 * iOS [CrashReportRepository] — the counterpart to `CrashReportRepositoryImpl`. Same storage shape
 * as Android: one `*.txt` per crash (so multiple crashes survive rather than overwriting), listed
 * newest-first by file modification time regardless of filename.
 *
 * Reports are written by [IosCrashReportRecorder], which hooks Kotlin/Native's unhandled-exception
 * path the way Android's FileCrashReportSender hooks Thread.UncaughtExceptionHandler.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCrashReportRepositoryImpl(private val fileStore: FileStore) : CrashReportRepository {

    override fun listCrashReports(): List<CrashReport> =
        reportFileNames().mapNotNull { fileName ->
            val path = reportPath(fileName)
            val content = IosAudiobookFiles.readText(path) ?: return@mapNotNull null
            CrashReport(
                id = fileName.removeSuffix(CRASH_REPORT_EXTENSION),
                content = content,
                timestampMillis = modifiedAtMillis(path),
            )
        }.sortedByDescending { it.timestampMillis }

    override fun resolveReportFilePaths(ids: List<String>): List<String> =
        ids.map { reportPath("$it$CRASH_REPORT_EXTENSION") }.filter { IosAudiobookFiles.exists(it) }

    override fun clearAllCrashReports() {
        reportFileNames().forEach { IosAudiobookFiles.deleteRecursively(reportPath(it)) }
    }

    /** Appends a crash report; the id is the caller's (timestamped) name. */
    fun record(id: String, content: String): Boolean =
        IosAudiobookFiles.writeText(reportPath("$id$CRASH_REPORT_EXTENSION"), content)

    private fun reportFileNames(): List<String> {
        val root = fileStore.resolve(NS_CRASH_REPORTS)

        @Suppress("UNCHECKED_CAST")
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, error = null) as? List<String>
            ?: return emptyList()
        return names.filter { it.endsWith(CRASH_REPORT_EXTENSION) }
    }

    private fun modifiedAtMillis(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0L
        val date = attributes[NSFileModificationDate] as? NSDate ?: return 0L
        return (date.timeIntervalSince1970 * 1000.0).toLong()
    }

    private fun reportPath(fileName: String) = fileStore.resolve(NS_CRASH_REPORTS, fileName)
}

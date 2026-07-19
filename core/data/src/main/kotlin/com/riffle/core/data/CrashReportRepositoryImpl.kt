package com.riffle.core.data

import com.riffle.core.data.di.CrashReportDir
import com.riffle.core.models.CrashReport
import com.riffle.core.domain.CrashReportRepository
import java.io.File
import javax.inject.Inject

/**
 * Lists crash reports stored in [reportDir] as individual `*.txt` files written by
 * [com.riffle.app.FileCrashReportSender]. One file per crash so multiple crashes survive
 * — the prior single-file design overwrote on every crash. Ordering is by file mtime so
 * the user sees the newest crash first regardless of filename collisions.
 */
class CrashReportRepositoryImpl @Inject constructor(
    @param:CrashReportDir private val reportDir: File,
) : CrashReportRepository {

    override fun listCrashReports(): List<CrashReport> {
        val files = reportDir.listFiles { f -> f.isFile && f.extension == EXT } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .map { CrashReport(id = it.nameWithoutExtension, content = it.readText(), timestampMillis = it.lastModified()) }
    }

    override fun resolveReportFiles(ids: List<String>): List<File> =
        ids.mapNotNull { id ->
            val f = File(reportDir, "$id.$EXT")
            f.takeIf { it.isFile }
        }

    override fun clearAllCrashReports() {
        reportDir.listFiles { f -> f.isFile && f.extension == EXT }?.forEach { it.delete() }
    }

    companion object {
        const val EXT = "txt"
    }
}

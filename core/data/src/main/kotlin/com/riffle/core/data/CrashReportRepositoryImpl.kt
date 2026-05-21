package com.riffle.core.data

import com.riffle.core.data.di.CrashReportFile
import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import java.io.File
import javax.inject.Inject

class CrashReportRepositoryImpl @Inject constructor(
    @CrashReportFile private val reportFile: File,
) : CrashReportRepository {

    override fun getLastCrashReport(): CrashReport? {
        if (!reportFile.exists()) return null
        return CrashReport(
            content = reportFile.readText(),
            timestampMillis = reportFile.lastModified(),
        )
    }
}

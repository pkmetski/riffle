package com.riffle.core.domain

import java.io.File
import com.riffle.core.models.CrashReport

interface CrashReportRepository {
    /** All recorded crashes, newest first. Empty when none have occurred since install. */
    fun listCrashReports(): List<CrashReport>

    /**
     * Files backing each [CrashReport] in [ids], in the same order. Used by the Settings
     * "Share" affordance to build an ACTION_SEND_MULTIPLE intent. Missing ids are skipped.
     */
    fun resolveReportFiles(ids: List<String>): List<File>

    /** Removes every recorded crash. */
    fun clearAllCrashReports()
}

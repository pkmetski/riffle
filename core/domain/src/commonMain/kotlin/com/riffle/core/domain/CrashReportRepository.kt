package com.riffle.core.domain

import com.riffle.core.models.CrashReport

interface CrashReportRepository {
    /** All recorded crashes, newest first. Empty when none have occurred since install. */
    fun listCrashReports(): List<CrashReport>

    /**
     * File paths backing each [CrashReport] in [ids], in the same order. Used by the Settings
     * "Share" affordance. Missing ids are skipped. Returns empty on platforms without crash files.
     */
    fun resolveReportFilePaths(ids: List<String>): List<String>

    /** Removes every recorded crash. */
    fun clearAllCrashReports()
}

package com.riffle.core.domain

interface CrashReportRepository {
    fun getLastCrashReport(): CrashReport?
}

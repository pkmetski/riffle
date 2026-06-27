package com.riffle.core.domain

/**
 * A single crash recorded by ACRA and written to disk. [id] is a stable, opaque token
 * (currently the on-disk filename without extension) used by the UI to expand/collapse a
 * specific report and by the share path to locate the underlying file.
 */
data class CrashReport(
    val id: String,
    val content: String,
    val timestampMillis: Long,
)

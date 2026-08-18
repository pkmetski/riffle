package com.riffle.core.domain.comic.panel

interface PanelReportRepository {
    /**
     * Commits [maskPng] to the `panel-reports` GitHub branch and creates an issue.
     * Returns the URL of the created issue on success.
     */
    suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String>
}

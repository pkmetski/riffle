package com.riffle.core.domain.comic.panel

interface PanelReportRepository {
    /**
     * Uploads [maskPng] to a GitHub gist and creates an issue linking it.
     * Returns the URL of the created issue on success.
     */
    suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String>
}

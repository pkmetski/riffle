package com.riffle.feature.library

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object Cached : DownloadState
    data class InProgress(val percent: Int? = null) : DownloadState
    data object Downloaded : DownloadState
}

sealed interface TocState {
    data object Loading : TocState
    data class Ready(val entries: List<com.riffle.core.models.TocEntry>) : TocState
}

sealed interface ChaptersState {
    data object Loading : ChaptersState
    data class Ready(val chapters: List<com.riffle.core.domain.AudiobookChapter>) : ChaptersState
}

internal fun readaloudDownloadStateFor(bundlePresent: Boolean): DownloadState =
    if (bundlePresent) DownloadState.Downloaded else DownloadState.NotDownloaded

internal fun downloadPercent(downloaded: Long, total: Long): Int? =
    if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null

internal fun estimatedReadingTimeSec(totalPositions: Int, secPerPosition: Double): Long? {
    if (totalPositions <= 0 || !secPerPosition.isFinite() || secPerPosition <= 0.0) return null
    return (totalPositions * secPerPosition).toLong().coerceAtLeast(0L)
}

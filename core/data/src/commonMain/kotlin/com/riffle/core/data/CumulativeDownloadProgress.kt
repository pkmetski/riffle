package com.riffle.core.data

/**
 * Owns the cumulative byte count and stable total for a transfer. One instance can track a single
 * book stream or successive audiobook tracks; a non-positive total remains indeterminate.
 *
 * Downloads are always serial within one item, so there is no concurrent access to guard against.
 */
internal class CumulativeDownloadProgress(
    total: Long,
    private val onProgress: (downloaded: Long, total: Long) -> Unit,
    initialDownloaded: Long = 0L,
) {
    private var downloaded = initialDownloaded.coerceAtLeast(0L)
    private var total = total.takeIf { it > 0L } ?: 0L

    fun hasKnownTotal(): Boolean = total > 0L

    fun establishTotal(candidate: Long?) {
        if (total == 0L && candidate != null && candidate > 0L) total = candidate
    }

    fun record(delta: Long) {
        if (delta <= 0L) return
        downloaded += delta
        onProgress(downloaded, total)
    }
}

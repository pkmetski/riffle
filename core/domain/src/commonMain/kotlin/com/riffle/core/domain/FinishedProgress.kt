package com.riffle.core.domain

/** The single definition of "finished" for a readingProgress fraction (mark-as-read writes 1.0). */
fun isFinishedReadingProgress(readingProgress: Float): Boolean = readingProgress >= 1f

/** A reader position at or below this fraction is still "on the cover". */
const val COVER_PROGRESS_EPSILON = 0.01f

/**
 * Mark-as-read is a one-shot reset, not a lock: it clears the position so the book opens from
 * the beginning, and any real reading afterwards un-finishes it — progress tracks the position
 * again, the book returns to In Progress and reopening resumes there. The one exception is
 * opening a finished book and closing it without leaving the cover: that must not drop it from
 * 100% to 0%. Returns true when [newProgress] must NOT replace [currentProgress].
 */
fun keepsFinishedState(currentProgress: Float, newProgress: Float): Boolean =
    isFinishedReadingProgress(currentProgress) && newProgress <= COVER_PROGRESS_EPSILON

/**
 * Returns the ebookProgress to push to the server, clamping to 1.0 when the reader is at the
 * last page of the book.
 *
 * In paginated and vertical modes, Readium's [totalProgression] for the last page is
 * `(N-1)/N` where N is the total number of positions — it never reaches 1.0 because positions
 * mark page starts, not ends. A user at the last page therefore pushes ~0.99 to ABS, which
 * displays as 99% even though the book is visually complete.
 *
 * In continuous mode the forward-boundary correction is applied upstream in
 * `ContinuousPositionTracker.adjustProgressionAtForwardBoundary` before the locator reaches
 * this function, so [totalProgression] arrives as 1.0 for the true end of the book.
 *
 * When [totalProgression] >= the last-page threshold and [positionCounts] are available,
 * the reader is on the last page of the book → return 1.0.
 * When [positionCounts] is empty (positions not yet loaded) no clamping is applied.
 */
fun finishAwareEbookProgress(
    totalProgression: Float?,
    progression: Float,
    positionCounts: List<Int>,
): Float {
    val raw = totalProgression ?: progression
    val total = positionCounts.sum()
    if (total <= 0) return raw
    val lastPageThreshold = (total - 1f) / total
    return if (raw >= lastPageThreshold) 1.0f else raw
}

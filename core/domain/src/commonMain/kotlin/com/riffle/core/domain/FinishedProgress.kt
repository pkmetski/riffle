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

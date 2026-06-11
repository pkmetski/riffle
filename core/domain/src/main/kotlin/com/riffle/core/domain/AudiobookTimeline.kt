package com.riffle.core.domain

/** An ABS chapter marker over the audiobook's single logical timeline, in seconds (ADR 0029). */
data class AudiobookChapter(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

/**
 * The play-position model for an [Audiobook]: total duration plus the ABS chapter markers (empty for
 * a chapterless single-file book). Pure position math for the [Audiobook Player] — current chapter,
 * chapter-relative time, and the previous/next-chapter seek targets — kept here so it is unit-testable
 * without a player or a device.
 */
data class AudiobookTimeline(
    val durationSec: Double,
    val chapters: List<AudiobookChapter> = emptyList(),
) {
    val hasChapters: Boolean get() = chapters.isNotEmpty()

    /** The chapter containing [positionSec], or null when the book has no chapter markers. */
    fun chapterAt(positionSec: Double): AudiobookChapter? {
        if (chapters.isEmpty()) return null
        // Last chapter whose start is at or before the position (chapters are start-ordered, contiguous).
        return chapters.lastOrNull { positionSec >= it.startSec } ?: chapters.first()
    }

    /**
     * Where the "previous chapter" button seeks from [positionSec]. Like mature audiobook players: if
     * already a little way into the current chapter, restart it; only if near its start jump to the
     * previous chapter. Null when there are no chapters (the button is disabled).
     */
    fun previousChapterTargetSec(positionSec: Double, restartThresholdSec: Double = RESTART_THRESHOLD_SEC): Double? {
        val current = chapterAt(positionSec) ?: return null
        if (positionSec - current.startSec > restartThresholdSec) return current.startSec
        val prev = chapters.getOrNull(current.index - 1) ?: return current.startSec
        return prev.startSec
    }

    /** Where the "next chapter" button seeks, or null when there is no later chapter / no chapters. */
    fun nextChapterTargetSec(positionSec: Double): Double? {
        val current = chapterAt(positionSec) ?: return null
        return chapters.getOrNull(current.index + 1)?.startSec
    }

    val canNextChapter: Boolean get() = hasChapters
    val canPreviousChapter: Boolean get() = hasChapters

    companion object {
        const val RESTART_THRESHOLD_SEC = 2.0
    }
}

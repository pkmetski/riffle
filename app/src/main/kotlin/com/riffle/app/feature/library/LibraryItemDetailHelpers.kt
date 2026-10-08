package com.riffle.app.feature.library

import com.riffle.core.models.EbookFormat
import com.riffle.feature.player.CompactDurationLabelTemplates
import com.riffle.feature.player.formatCompactDuration
import kotlin.math.roundToInt

/**
 * Pure helper functions for the item-detail screen. Extracted so that JVM tests in the
 * same package can call them without needing a Compose or Android environment.
 */

/** EPUB reading-time line. Mirrors [com.riffle.feature.library.ui.ebookReadingTimeText]. */
internal fun ebookReadingTimeText(
    totalSec: Long,
    readingProgress: Float,
    estimated: (String) -> String = { "$it estimated" },
    estimatedTotal: (String) -> String = { "$it estimated total" },
    estimatedTotalRemaining: (String, String) -> String = { total, rem -> "$total estimated total · $rem remaining" },
): String {
    val total = formatCompactDuration(totalSec.toDouble())
    return when {
        readingProgress >= 0.99f -> estimatedTotal(total)
        readingProgress > 0f -> {
            val remainingSec = ((1f - readingProgress) * totalSec).toLong().coerceAtLeast(0L)
            estimatedTotalRemaining(total, formatCompactDuration(remainingSec.toDouble()))
        }
        else -> estimated(total)
    }
}

internal fun progressPercent(progress: Float): Int =
    (progress * 100).roundToInt().coerceIn(0, 100)

/**
 * Returns true for formats whose publication-facts data (reading-time estimate, page count)
 * arrives asynchronously after first render. Space is reserved from frame 1 so the action
 * row does not reflow when the value lands.
 */
internal fun publicationFactsLineReservesSpace(format: EbookFormat): Boolean =
    format == EbookFormat.Epub || format == EbookFormat.Pdf

/**
 * Formats the publication page-count line. [readingProgress] 0 = not started, 1 = finished.
 * Mirrors [com.riffle.feature.library.ui.pageCountText] but as a stable :app-internal overload
 * so JVM tests can call it without crossing internal module boundaries.
 */
internal fun publicationPageCountText(
    pageCount: Int,
    readingProgress: Float,
    formatPages: (Int) -> String = { "$it pages" },
    formatPagesRead: (Int, Int) -> String = { read, total -> "$read of $total pages read" },
): String {
    if (pageCount <= 0) return ""
    val progress = readingProgress
    return if (!progress.isFinite() || progress <= 0f) {
        formatPages(pageCount)
    } else {
        val read = (pageCount * progress).roundToInt().coerceIn(1, pageCount)
        formatPagesRead(read, pageCount)
    }
}

/**
 * Formats the audiobook duration line with injectable format lambdas so tests can supply
 * localized label templates without a Compose environment.
 */
internal fun audiobookDurationLineText(
    durationSec: Double,
    readingProgress: Float,
    durationLabels: CompactDurationLabelTemplates,
    audiobookDuration: (String) -> String = { it },
    durationTotalRemaining: (String, String) -> String = { total, rem -> "$total · $rem" },
): String {
    fun fmt(sec: Double): String = formatCompactDuration(sec, durationLabels)
    val total = fmt(durationSec)
    return when {
        readingProgress >= 0.99f -> total
        readingProgress > 0f -> {
            val remainingSec = ((1f - readingProgress) * durationSec).coerceAtLeast(0.0)
            durationTotalRemaining(total, fmt(remainingSec))
        }
        else -> audiobookDuration(total)
    }
}

package com.riffle.feature.player

import com.riffle.core.domain.AudiobookChapter

/** mm:ss under an hour, h:mm:ss otherwise. */
fun formatHms(totalSec: Double): String {
    val s = totalSec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    val mStr = m.toString().padStart(2, '0')
    val secStr = sec.toString().padStart(2, '0')
    return if (h > 0) "$h:$mStr:$secStr" else "$m:$secStr"
}

/**
 * Human-readable remaining-time label for the system media notification / lock-screen player —
 * e.g. "3h 12m left", "45m left", "Less than a minute left". Whole-minute granularity so we
 * update the notification metadata at most once per minute.
 */
fun formatRemainingReadable(remainingSec: Double): String {
    val totalMinutes = (remainingSec / 60.0).toLong().coerceAtLeast(0)
    if (totalMinutes == 0L) return "Less than a minute left"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m left" else "${m}m left"
}

/**
 * Artist line for the system media notification: "Chapter Title · 3h 12m left" when [chapter] is
 * non-null, or just the remaining time otherwise.
 */
fun notificationArtistText(chapter: AudiobookChapter?, remainingSec: Double): String {
    val remaining = formatRemainingReadable(remainingSec)
    return if (chapter != null) "${chapter.title} · $remaining" else remaining
}

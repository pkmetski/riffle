package com.riffle.feature.player

import kotlin.math.roundToInt

/**
 * The book-absolute resume position. When NO position was tracked at all ([hadTrackedPosition] false —
 * no local audiobook row and no server record, e.g. offline with only a downloaded bundle) fall back
 * to the item's library [readingProgressFraction] mapped to seconds. This resumes near where the rest
 * of the app shows progress instead of restarting at 0, and (crucially) seeds the resume floor so the
 * close/follow persist guard never writes a ~0 over that progress — the offline-erase bug.
 *
 * Gating on [hadTrackedPosition] — not on `reconciledSec > 0` — is deliberate: a genuinely-tracked
 * position of exactly 0 (a server record or local row that says "at the start", with a real
 * timestamp) must be honoured, not replaced by the progress fallback. Online behaviour is unchanged
 * because a server record always counts as a tracked position.
 */
fun audiobookResumeSec(
    reconciledSec: Double,
    hadTrackedPosition: Boolean,
    readingProgressFraction: Float,
    durationSec: Double,
): Double =
    if (hadTrackedPosition || readingProgressFraction <= 0f || durationSec <= 0.0) reconciledSec
    else (readingProgressFraction * durationSec).coerceIn(0.0, durationSec)

/** How close to the end a resume position must sit to count the book as finished (and restart at 0). */
const val AUDIOBOOK_FINISHED_EPS_SEC: Double = 1.0

/**
 * The position to actually start a normally-opened audiobook at. A resume that lands at (or within
 * [AUDIOBOOK_FINISHED_EPS_SEC] of) the end means the book was finished: seeding the player there puts
 * it at the end of the last track, where ExoPlayer is `STATE_ENDED` and `play()` is a no-op — the
 * player sits silent with the seek bar pinned at the end. Reopening a finished book is a replay
 * intent, so restart from the beginning instead. A position anywhere short of the end is honoured
 * as-is. No-op when the duration is unknown (we can't tell what "the end" is).
 */
fun audiobookStartSec(resumeSec: Double, durationSec: Double): Double =
    if (durationSec > 0.0 && resumeSec >= durationSec - AUDIOBOOK_FINISHED_EPS_SEC) 0.0 else resumeSec

/**
 * Maps a book-absolute listen position to the unified 0..1 `readingProgress` fraction the library
 * and detail screens render (ADR 0035). Returns 0 when the duration isn't known yet (so a not-yet-
 * prepared player never writes a bogus 100%). Snaps to 1f within [AUDIOBOOK_FINISHED_EPS_SEC] of
 * the end so a book listened all the way through displays 100%, not 99% — LAME encoder-delay/
 * padding samples and the silent Xing/Info frame leave the reported position a hair short of the
 * Xing-derived total, and integer truncation at the display sites would otherwise render "99%".
 */
fun audiobookProgressFraction(positionSec: Double, durationSec: Double): Float =
    when {
        durationSec <= 0.0 -> 0f
        positionSec >= durationSec - AUDIOBOOK_FINISHED_EPS_SEC -> 1f
        else -> (positionSec / durationSec).toFloat().coerceIn(0f, 1f)
    }

/**
 * The one-line facts shown beneath the cover on the landscape player. Duration is omitted when
 * unknown; at most two genres are listed to keep it tidy. Null when there's nothing to show.
 */
data class CompactDurationLabelTemplates(
    val minutes: String = "%1\$dm",
    val hours: String = "%1\$dh",
    val hoursMinutes: String = "%1\$dh %2\$dm",
)

fun formatCompactDuration(
    durationSec: Double,
    templates: CompactDurationLabelTemplates = CompactDurationLabelTemplates(),
    roundToNearestMinute: Boolean = false,
): String {
    val totalMinutes = if (roundToNearestMinute) {
        (durationSec / 60.0).roundToInt().coerceAtLeast(0)
    } else {
        (durationSec.toLong().coerceAtLeast(0) / 60).toInt()
    }
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> applyPositionalTemplate(templates.hoursMinutes, hours, minutes)
        hours > 0 -> applyPositionalTemplate(templates.hours, hours)
        else -> applyPositionalTemplate(templates.minutes, minutes)
    }
}

fun buildAudiobookFacts(
    durationSec: Double,
    genres: List<String>,
    audiobookLabel: String = "Audiobook",
    durationLabels: CompactDurationLabelTemplates = CompactDurationLabelTemplates(),
): String? {
    val parts = buildList {
        add(audiobookLabel)
        if (durationSec > 0.0) {
            add(formatCompactDuration(durationSec, durationLabels))
        }
        genres.take(2).forEach { add(it) }
    }
    // The medium label alone has no real facts, so it is not worth a line.
    return if (parts.size > 1) parts.joinToString(" · ") else null
}

/**
 * Replaces `%1$d`, `%2$d` … positional integer specifiers in [template] with [values] in order.
 * Used in lieu of `String.format()` which is JVM-only; keeps [CompactDurationLabelTemplates]
 * localizable without platform-specific APIs.
 */
private fun applyPositionalTemplate(template: String, vararg values: Int): String {
    var result = template
    values.forEachIndexed { index, value ->
        result = result.replace("%${index + 1}\$d", value.toString())
    }
    return result
}

/** Whether the reader's readaloud control is shown, and whether it can be tapped. */
data class ReadaloudControlState(val visible: Boolean, val enabled: Boolean)

/**
 * Storyteller books always show an enabled control; a matched ABS book also shows an enabled control;
 * an unmatched ABS book shows no control at all.
 */
fun readaloudControlState(
    isStoryteller: Boolean,
    isMatchedAbs: Boolean,
    @Suppress("UNUSED_PARAMETER") bundlePresent: Boolean,
): ReadaloudControlState = when {
    isStoryteller -> ReadaloudControlState(visible = true, enabled = true)
    isMatchedAbs -> ReadaloudControlState(visible = true, enabled = true)
    else -> ReadaloudControlState(visible = false, enabled = false)
}

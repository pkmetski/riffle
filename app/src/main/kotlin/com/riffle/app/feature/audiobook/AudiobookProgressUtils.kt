package com.riffle.app.feature.audiobook

// AudiobookProgressUtils functions have moved to feature:player.
// Re-exported here for backward compatibility with app-internal callers.
val AUDIOBOOK_FINISHED_EPS_SEC = com.riffle.feature.player.AUDIOBOOK_FINISHED_EPS_SEC

fun audiobookProgressFraction(positionSec: Double, durationSec: Double): Float =
    com.riffle.feature.player.audiobookProgressFraction(positionSec, durationSec)

fun audiobookResumeSec(
    reconciledSec: Double,
    hadTrackedPosition: Boolean,
    readingProgressFraction: Float,
    durationSec: Double,
): Double = com.riffle.feature.player.audiobookResumeSec(reconciledSec, hadTrackedPosition, readingProgressFraction, durationSec)

fun audiobookStartSec(resumeSec: Double, durationSec: Double): Double =
    com.riffle.feature.player.audiobookStartSec(resumeSec, durationSec)

fun buildAudiobookFacts(
    durationSec: Double,
    genres: List<String>,
    audiobookLabel: String = "Audiobook",
    durationLabels: com.riffle.feature.player.CompactDurationLabelTemplates = com.riffle.feature.player.CompactDurationLabelTemplates(),
): String? = com.riffle.feature.player.buildAudiobookFacts(durationSec, genres, audiobookLabel, durationLabels)

typealias CompactDurationLabelTemplates = com.riffle.feature.player.CompactDurationLabelTemplates

fun formatCompactDuration(
    durationSec: Double,
    templates: CompactDurationLabelTemplates = CompactDurationLabelTemplates(),
    roundToNearestMinute: Boolean = false,
): String = com.riffle.feature.player.formatCompactDuration(durationSec, templates, roundToNearestMinute)

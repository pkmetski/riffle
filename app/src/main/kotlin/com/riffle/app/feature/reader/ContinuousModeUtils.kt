package com.riffle.app.feature.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Computes a book-wide [0, 1] progression for continuous scroll mode using the same
 * per-chapter weights the chapter rail draws from (derived from Readium position counts).
 *
 * Returns null when [segments] is empty, total weight is zero, or [href] has no matching
 * segment.
 */
fun computeTotalProgression(
    href: String,
    progression: Float,
    segments: List<RailSegment>,
): Float? {
    val hrefBase = href.substringBefore('#')
    val idx = segments.indexOfFirst { it.href.substringBefore('#') == hrefBase }
    if (idx < 0) return null
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight == 0f) return null
    val cumulativeWeight = segments.take(idx).sumOf { it.weight.toDouble() }.toFloat()
    val chapterWeight = segments[idx].weight
    return (cumulativeWeight + chapterWeight * progression) / totalWeight
}

/**
 * Builds the JSON object consumed by [Locator.fromJSON] for a continuous-mode position.
 *
 * Paginated and vertical modes receive a fully-populated [Locator] (including
 * [Locator.Locations.totalProgression]) from Readium automatically. Continuous mode only knows
 * the spine [href] and within-chapter [progression], so this function derives
 * `totalProgression` from the rail segment weights and embeds it in the JSON — giving the
 * ViewModel the same input for the chapter-map rail cursor and reading-time estimates as the
 * other two modes.
 *
 * Separated from [buildContinuousLocator] so the JSON output is testable without
 * [android.net.Uri] (which [Locator.fromJSON] needs and JVM tests cannot provide).
 */
fun buildContinuousLocatorJson(
    href: String,
    progression: Float,
    segments: List<RailSegment>,
): JSONObject {
    val totalProg = computeTotalProgression(href, progression, segments)
    val locations = JSONObject().put("progression", progression.toDouble())
    if (totalProg != null) locations.put("totalProgression", totalProg.toDouble())
    return JSONObject()
        .put("href", href)
        .put("type", "application/xhtml+xml")
        .put("locations", locations)
}

/**
 * Converts a raw continuous-mode position into a [Locator] that the ViewModel can consume.
 * Thin wrapper over [buildContinuousLocatorJson] + [Locator.fromJSON].
 */
fun buildContinuousLocator(
    href: String,
    progression: Float,
    segments: List<RailSegment>,
): Locator? = Locator.fromJSON(buildContinuousLocatorJson(href, progression, segments))

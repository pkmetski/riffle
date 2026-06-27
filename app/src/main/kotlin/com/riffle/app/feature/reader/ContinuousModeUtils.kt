package com.riffle.app.feature.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Computes a book-wide [0, 1] progression for continuous scroll mode from the spine's per-resource
 * position counts (the same data Readium uses to derive `totalProgression` in paginated/vertical
 * modes), given the current spine resource [href] and within-resource [progression] (0..1 across
 * the loaded resource's pixel height).
 *
 * Why spine-based, not rail-segment-based: a single rail segment can map to several spine
 * resources when a TOC entry is the sole entry for its file and the next TOC entry lives in a
 * later file (e.g. "Chapter 20" → SOL 376, SOL 380, SOL 384 in The Martian). The continuous
 * reader reports `progression` per-RESOURCE, but a segment-based formula would multiply it by the
 * segment's full weight — making the cursor and time estimates advance ~N× too fast inside the
 * first resource of an N-resource segment, then jamming for the remaining resources (whose href
 * doesn't match any segment at all and returns null). Spine positions are the right grain.
 *
 * Returns null when [spineHrefs] / [positionCounts] are empty/zero or [href] is not in the spine.
 */
fun computeTotalProgression(
    href: String,
    progression: Float,
    spineHrefs: List<String>,
    positionCounts: List<Int>,
): Float? {
    val hrefBase = href.substringBefore('#')
    val idx = spineHrefs.indexOfFirst { it.substringBefore('#') == hrefBase }
    if (idx < 0) return null
    val total = positionCounts.sumOf { it.toLong() }
    if (total <= 0L) return null
    var before = 0L
    for (i in 0 until idx) before += positionCounts.getOrElse(i) { 0 }.toLong()
    val resourcePositions = positionCounts.getOrElse(idx) { 0 }.toFloat()
    val clamped = progression.coerceIn(0f, 1f)
    return ((before + resourcePositions * clamped) / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * Builds the JSON object consumed by [Locator.fromJSON] for a continuous-mode position.
 *
 * Paginated and vertical modes receive a fully-populated [Locator] (including
 * [Locator.Locations.totalProgression]) from Readium automatically. Continuous mode only knows
 * the spine [href] and within-resource [progression], so this function derives `totalProgression`
 * from the spine's per-resource position counts and embeds it in the JSON — giving the ViewModel
 * the same input for the chapter-map rail cursor and reading-time estimates as the other two
 * modes.
 *
 * Separated from [buildContinuousLocator] so the JSON output is testable without
 * [android.net.Uri] (which [Locator.fromJSON] needs and JVM tests cannot provide).
 */
fun buildContinuousLocatorJson(
    href: String,
    progression: Float,
    spineHrefs: List<String>,
    positionCounts: List<Int>,
): JSONObject {
    val totalProg = computeTotalProgression(href, progression, spineHrefs, positionCounts)
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
    spineHrefs: List<String>,
    positionCounts: List<Int>,
): Locator? = Locator.fromJSON(buildContinuousLocatorJson(href, progression, spineHrefs, positionCounts))

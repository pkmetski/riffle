package com.riffle.app.feature.reader

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
    val idx = segments.indexOfFirst { it.href == href }
    if (idx < 0) return null
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight == 0f) return null
    val cumulativeWeight = segments.take(idx).sumOf { it.weight.toDouble() }.toFloat()
    val chapterWeight = segments[idx].weight
    return (cumulativeWeight + chapterWeight * progression) / totalWeight
}

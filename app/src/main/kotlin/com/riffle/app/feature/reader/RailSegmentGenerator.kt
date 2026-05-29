package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>, bookTitle: String = ""): List<RailSegment> {
    val bookTitleNorm = bookTitle.normalize()
    return tocEntries.flatMap { expandIfRedundant(it, bookTitleNorm) }
}

private fun expandIfRedundant(entry: TocEntry, bookTitleNorm: String): List<RailSegment> {
    val isRedundantContainer = entry.children.isNotEmpty() && (
        entry.title.isBlank() ||
            (bookTitleNorm.isNotEmpty() && entry.title.normalize() == bookTitleNorm)
        )
    return if (isRedundantContainer) {
        entry.children.flatMap { expandIfRedundant(it, bookTitleNorm) }
    } else {
        listOf(RailSegment(entry.title, entry.href))
    }
}

private fun String.normalize(): String = trim().lowercase().replace(Regex("\\s+"), " ")

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    return if (baseMatch >= 0) baseMatch else 0
}

/**
 * Assign each segment a weight proportional to the length of its spine resource. When several
 * segments map to the same spine resource (e.g. multiple TOC subsections of one chapter file),
 * the resource's length is split equally between them. Segments whose href doesn't resolve to a
 * spine resource, or whose resource has no positions, get weight 1f as a neutral fallback.
 *
 * Pure function so the math is unit-testable without a Readium [Publication].
 */
fun weightSegmentsByChapterLength(
    segments: List<RailSegment>,
    spineHrefs: List<String>,
    positionCounts: List<Int>,
): List<RailSegment> {
    if (segments.isEmpty()) return segments
    val hrefToSpine: Map<String, Int> = spineHrefs.withIndex().associate { (i, h) -> h to i }
    val spineForSegment: List<Int?> = segments.map { hrefToSpine[it.href.substringBefore('#')] }
    val countsBySpine: Map<Int, Int> = spineForSegment
        .filterNotNull()
        .groupingBy { it }
        .eachCount()
    return segments.mapIndexed { i, seg ->
        val spine = spineForSegment[i]
        val length = spine?.let { positionCounts.getOrNull(it) } ?: 0
        val share = if (spine != null && length > 0) {
            length.toFloat() / (countsBySpine[spine] ?: 1)
        } else 1f
        seg.copy(weight = share)
    }
}

/** Start x and width per segment, laid out across [totalWidth] in proportion to segment weights. */
fun railSegmentBounds(segments: List<RailSegment>, totalWidth: Float): List<Pair<Float, Float>> {
    if (segments.isEmpty() || totalWidth <= 0f) return emptyList()
    val effective = effectiveWeights(segments)
    val totalWeight = effective.sum()
    val out = ArrayList<Pair<Float, Float>>(segments.size)
    var acc = 0f
    var prevX = 0f
    for (w in effective) {
        acc += w
        val nextX = (acc / totalWeight) * totalWidth
        out.add(prevX to (nextX - prevX))
        prevX = nextX
    }
    return out
}

private fun effectiveWeights(segments: List<RailSegment>): FloatArray {
    val raw = FloatArray(segments.size) { segments[it].weight.coerceAtLeast(0f) }
    val sum = raw.sum()
    if (sum > 0f) return raw
    // All-zero (or negative) weights: fall back to equal weighting so the layout still spans the rail.
    return FloatArray(segments.size) { 1f }
}

/**
 * Cursor x as a fraction of total rail width (0..1). The cursor is placed at
 * `progression` within the [activeIndex] segment, using the weighted layout so it always lands
 * inside the active segment's bounds regardless of segment widths.
 */
fun weightedRailCursorPosition(
    activeIndex: Int,
    segments: List<RailSegment>,
    progression: Float,
): Float {
    if (segments.isEmpty()) return 0f
    val effective = effectiveWeights(segments)
    val totalWeight = effective.sum()
    val i = activeIndex.coerceIn(0, segments.size - 1)
    var cum = 0f
    for (k in 0 until i) cum += effective[k]
    val w = effective[i]
    return ((cum + progression.coerceIn(0f, 1f) * w) / totalWeight).coerceIn(0f, 1f)
}

/** Returns the segment index at horizontal pixel [x] for the weighted layout across [totalWidth]. */
fun railSegmentIndexAt(segments: List<RailSegment>, x: Float, totalWidth: Float): Int {
    if (segments.isEmpty()) return -1
    val effective = effectiveWeights(segments)
    val totalWeight = effective.sum()
    val target = (x.coerceIn(0f, totalWidth) / totalWidth) * totalWeight
    var acc = 0f
    for (i in segments.indices) {
        acc += effective[i]
        if (target <= acc) return i
    }
    return segments.size - 1
}

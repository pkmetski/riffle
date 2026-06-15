package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>, bookTitle: String = ""): List<RailSegment> {
    val bookTitleNorm = bookTitle.normalize()
    val expanded = tocEntries.flatMap { expandIfRedundant(it, bookTitleNorm) }
    // Collapse entries that point to the same spine resource via different fragments. The
    // navigator's locator carries no fragment during natural reading, so only the first
    // matching segment would ever become active; siblings would visually skip on chapter
    // transitions. Keep the first occurrence per base href so the rail shows one segment
    // per spine resource.
    val seen = HashSet<String>(expanded.size)
    return expanded.filter { seen.add(it.href.substringBefore('#')) }
}

private fun expandIfRedundant(entry: TocEntry, bookTitleNorm: String): List<RailSegment> {
    val isRedundantContainer = entry.children.isNotEmpty() && (
        entry.title.isBlank() ||
            (bookTitleNorm.isNotEmpty() && entry.title.normalize() == bookTitleNorm)
        )
    if (isRedundantContainer) {
        return entry.children.flatMap { expandIfRedundant(it, bookTitleNorm) }
    }
    // If this entry's children point to different spine files it's a grouping container
    // (e.g. "Part I" whose TOC children are actual chapter files). Expand into children so
    // estimates are per-chapter rather than per-part.
    val entryBaseHref = entry.href.substringBefore('#')
    if (entry.children.isNotEmpty() &&
        entry.children.any { it.href.substringBefore('#') != entryBaseHref }
    ) {
        return entry.children.flatMap { expandIfRedundant(it, bookTitleNorm) }
    }
    return listOf(RailSegment(entry.title, entry.href))
}

private fun String.normalize(): String = trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Resolve the rail segment that owns [currentHref].
 *
 * EPUB spines often contain resources with no TOC entry (separators, ornamental pages between
 * parts). When the navigator emits a locator for such a resource, falling back to segment 0
 * causes the chapter label to flicker to "Chapter 1" between adjacent chapters. Pass
 * [spineHrefs] (the publication's reading order) so the fallback can pick the last segment
 * whose spine position is at-or-before the current resource — the chapter the user is "inside"
 * by spine order — instead of snapping to the book's first chapter.
 */
fun findActiveSegmentIndex(
    segments: List<RailSegment>,
    currentHref: String,
    spineHrefs: List<String> = emptyList(),
): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    if (baseMatch >= 0) return baseMatch
    if (spineHrefs.isEmpty()) return 0
    val spineBases = spineHrefs.map { it.substringBefore('#') }
    val currentSpineIndex = spineBases.indexOf(currentBase)
    if (currentSpineIndex < 0) return 0
    var bestSegIdx = -1
    var bestSpineIdx = -1
    segments.forEachIndexed { i, seg ->
        val segSpineIdx = spineBases.indexOf(seg.href.substringBefore('#'))
        if (segSpineIdx in 0..currentSpineIndex && segSpineIdx > bestSpineIdx) {
            bestSpineIdx = segSpineIdx
            bestSegIdx = i
        }
    }
    return if (bestSegIdx >= 0) bestSegIdx else 0
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
    // When multiple TOC entries point to the same spine resource (sub-sections of one file),
    // split that file's positions equally across them. When a TOC entry is the sole entry for
    // its resource, accumulate positions for ALL spine resources up to the next TOC entry —
    // this handles EPUBs where chapter files have no individual TOC entries and are grouped
    // under a part/section title page.
    val sharedCounts: Map<Int, Int> = spineForSegment
        .filterNotNull()
        .groupingBy { it }
        .eachCount()
    return segments.mapIndexed { i, seg ->
        val spineStart = spineForSegment[i] ?: return@mapIndexed seg.copy(weight = 1f)
        val share = if ((sharedCounts[spineStart] ?: 1) > 1) {
            val length = positionCounts.getOrElse(spineStart) { 0 }
            if (length > 0) length.toFloat() / (sharedCounts[spineStart] ?: 1) else 1f
        } else {
            val nextSpineIdx = ((i + 1)..segments.lastIndex)
                .firstNotNullOfOrNull { j -> spineForSegment[j] }
                ?: spineHrefs.size
            val total = (spineStart until nextSpineIdx).sumOf { positionCounts.getOrElse(it) { 0 } }
            if (total > 0) total.toFloat() else 1f
        }
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

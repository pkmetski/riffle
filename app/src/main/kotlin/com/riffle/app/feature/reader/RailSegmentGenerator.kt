package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry

fun buildRailSegments(
    tocEntries: List<TocEntry>,
    bookTitle: String = "",
    spineHrefs: List<String> = emptyList(),
    positionCounts: List<Int> = emptyList(),
): List<RailSegment> {
    val bookTitleNorm = bookTitle.normalize()
    val spineIndex = spineIndexOf(spineHrefs)
    val preprocessed = absorbFlatNumericRuns(tocEntries)
    val expanded = preprocessed.flatMap { expandIfRedundant(it, bookTitleNorm, spineIndex, positionCounts) }
    // Collapse entries that point to the same spine resource via different fragments. The
    // navigator's locator carries no fragment during natural reading, so only the first
    // matching segment would ever become active; siblings would visually skip on chapter
    // transitions. Keep the first occurrence per base href so the rail shows one segment
    // per spine resource.
    val seen = HashSet<String>(expanded.size)
    return expanded.filter { seen.add(it.href.substringBefore('#')) }
}

private fun expandIfRedundant(
    entry: TocEntry,
    bookTitleNorm: String,
    spineIndex: Map<String, Int>,
    positionCounts: List<Int>,
): List<RailSegment> {
    if (shouldReplaceWithChildren(entry, bookTitleNorm, spineIndex, positionCounts)) {
        return entry.children.flatMap { expandIfRedundant(it, bookTitleNorm, spineIndex, positionCounts) }
    }
    return listOf(RailSegment(entry.title, entry.href))
}

/**
 * Should this TOC entry be replaced by its children on the rail? Two orthogonal signals,
 * each vetted against real books; see `RailSegmentGeneratorTest` for the fixtures.
 *
 * 1. **Transparent container** — a blank title or one that just repeats the book title carries
 *    no navigational identity; its children *are* the structure (e.g. single-file EPUBs whose
 *    only top-level TOC entry is the book title wrapping the actual chapters). This branch
 *    fires even when the children are same-file `#anchors`, which is the case that signal 2
 *    can't reach: it's the only way to relabel a redundant wrapper to its first child's title.
 * 2. **Length-based expansion** (positions supplied) — a titled parent whose spine file is
 *    dramatically shorter than the bulk of its cross-file children is a section-title-page
 *    grouping container ("Part I" → chapter files, "Силян Щърка"'s nav-supplied "2" wrapper
 *    → folk tales). The rule uses three guards on the cross-file children:
 *      - EVERY child must have a positive length (a 0-length child signals a stub/malformed
 *        entry — err on the side of not expanding).
 *      - A child is "substantial" when it is BOTH at least twice the parent's length AND at
 *        least [MIN_SUBSTANTIAL_POSITIONS] positions in absolute terms. The absolute floor
 *        matters when the parent itself is tiny (1 position): "1001 Nights" Pattern B stories
 *        like "Четвърта глава. Детето съдия" have parent=1 and children of 1–3 positions —
 *        several trivially clear the 2× bar but are still flash-fiction-length. The floor
 *        prevents these from being called substantial.
 *      - STRICTLY MORE THAN HALF of the children must be substantial. Using majority instead
 *        of "all" tolerates the tail-length variation that real anthologies exhibit (Silyan's
 *        tales range 2–31 positions with a 3-position title-page parent — several are close
 *        to the parent's length, but 13 of 19 stories are ≥ 2× parent AND ≥ floor).
 *    Requiring 2× rather than just >= parent rejects the Bulgarian "1001 Nights" pattern
 *    where a story-container is the same order of magnitude as each of its short sub-
 *    chapters. The rule also rejects "Chapter → short annotations" (The Martian: SOL entries
 *    shorter than the chapter that contains them).
 * 3. **Grandchildren OR length** — when position info is present, either signal alone is
 *    enough to expand. Grandchildren catches 3-level books where the direct children are
 *    themselves short sub-part containers ("Старогръцки легенди" Part → Богове/Герои →
 *    individual gods): length looks at the short direct children and would collapse, but
 *    the grandchildren prove this is a hierarchical grouping worth expanding. Length
 *    catches the leaf-chapter cases where grandchildren doesn't fire (Barkley, Silyan). The
 *    Martian-shaped invariant (Chapter → short leaf annotations) is safe under either signal
 *    — no grandchildren AND length majority fails.
 *
 *    When positions are absent (older callers, test fixtures without a spine), fall back to
 *    the grandchildren signal alone.
 */
private fun shouldReplaceWithChildren(
    entry: TocEntry,
    bookTitleNorm: String,
    spineIndex: Map<String, Int>,
    positionCounts: List<Int>,
): Boolean {
    if (entry.children.isEmpty()) return false

    // (1) Transparent container. Fires even when children are same-file anchors — this is
    // the branch that relabels a book-title-only wrapper to its first chapter's title in
    // legacy single-file EPUBs. Redundant in production for cross-file children (rule 2's
    // length branch covers those), kept as a small hedge against the same-file-anchor
    // shape and as an explicit textual signal.
    if (entry.title.isBlank()) return true
    if (bookTitleNorm.isNotEmpty() && entry.title.normalize() == bookTitleNorm) return true

    // (2) Grandchildren OR length. Either signal alone is enough — grandchildren catches
    // hierarchical parents whose direct children are short sub-part containers; length catches
    // leaf-chapter parents where grandchildren is absent. Same-file anchor children are
    // excluded from crossFileChildren; if that leaves nothing, both branches naturally return
    // false without a separate short-circuit (grandchildren.any → false, length majority
    // 0*2 > 0 → false).
    val entryBaseHref = entry.href.substringBefore('#')
    val crossFileChildren = entry.children.filter { it.href.substringBefore('#') != entryBaseHref }
    val hasGrandchildren = crossFileChildren.any { it.children.isNotEmpty() }
    if (spineIndex.isEmpty() || positionCounts.isEmpty()) {
        return hasGrandchildren
    }
    if (hasGrandchildren) return true
    val parentLen = spineLength(entry.href, spineIndex, positionCounts)
    val childLens = crossFileChildren.map { spineLength(it.href, spineIndex, positionCounts) }
    if (childLens.any { it <= 0 }) return false
    val substantialThreshold = maxOf(2 * parentLen, MIN_SUBSTANTIAL_POSITIONS)
    val substantial = childLens.count { it >= substantialThreshold }
    return substantial * 2 > childLens.size
}

/**
 * Rewrite the top-level TOC so that flat "N. Sub-chapter" runs get nested under the bare
 * parent that precedes them.
 *
 * Real case: "Приказки от хиляда и една нощ" (1001 Nights collections) list each story
 * title as a bare top-level entry and its sub-chapters ("1. Бурята", "2. Магнитната планина",
 * … "16. Край на приказката") as siblings at the same TOC level instead of children. Without
 * preprocessing the rail shows 100+ tiny segments; with it, the length rule downstream keeps
 * each story collapsed as one segment (sub-chapters fail the substantial threshold).
 *
 * Guarded conservatively to avoid false positives on genuinely-numbered top-level chapters:
 * - At least two distinct numeric-prefix runs in the top-level TOC (a single run is more
 *   likely a real numbered chapter list — Preface + 1. Intro / 2. Body / 3. Conclusion).
 * - Each absorbable run starts at 1 — the restart is proof it's a subordinate sequence.
 * - The bare parent must have no existing children (otherwise we'd clobber intentional
 *   hierarchy from the NCX).
 */
private fun absorbFlatNumericRuns(toc: List<TocEntry>): List<TocEntry> {
    fun numericIndex(title: String): Int? = NUMERIC_PREFIX.find(title)?.groupValues?.get(1)?.toIntOrNull()

    data class Run(val range: IntRange, val startNumber: Int)
    val runs = mutableListOf<Run>()
    var i = 0
    while (i < toc.size) {
        val n = numericIndex(toc[i].title)
        if (n == null) { i++; continue }
        val start = i
        val startNum = n
        while (i < toc.size && numericIndex(toc[i].title) != null) i++
        runs.add(Run(start until i, startNum))
    }
    val absorbable = runs.filter { r ->
        r.startNumber == 1 &&
            r.range.first > 0 &&
            numericIndex(toc[r.range.first - 1].title) == null &&
            toc[r.range.first - 1].children.isEmpty()
    }
    if (absorbable.size < 2) return toc

    val absorbedIdx: Set<Int> = absorbable.flatMap { it.range.toList() }.toSet()
    val runByParent: Map<Int, List<TocEntry>> =
        absorbable.associate { r -> (r.range.first - 1) to r.range.map { toc[it] } }
    return toc.mapIndexedNotNull { idx, entry ->
        when {
            idx in absorbedIdx -> null
            idx in runByParent -> entry.copy(children = entry.children + runByParent.getValue(idx))
            else -> entry
        }
    }
}

private fun spineLength(href: String, spineIndex: Map<String, Int>, positionCounts: List<Int>): Int {
    val i = spineIndex[href.substringBefore('#')] ?: return 0
    return positionCounts.getOrElse(i) { 0 }
}

// Readium position units are ~1024 chars each; 3 positions ≈ 3 KB of text ≈ a couple of
// paperback pages. Below this a "chapter" is really a fragment and shouldn't earn its own
// rail segment even if it happens to be twice the length of a still-tinier parent.
private const val MIN_SUBSTANTIAL_POSITIONS = 3

private val NUMERIC_PREFIX = Regex("""^\s*(\d+)\s*[.)]\s+""")

/** Shared spine-href → index lookup used by both the expand decision and the weight math. */
private fun spineIndexOf(spineHrefs: List<String>): Map<String, Int> =
    spineHrefs.withIndex().associate { (i, h) -> h to i }

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
    val hrefToSpine = spineIndexOf(spineHrefs)
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

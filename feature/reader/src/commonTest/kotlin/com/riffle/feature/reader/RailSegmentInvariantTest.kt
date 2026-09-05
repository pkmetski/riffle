package com.riffle.feature.reader

import com.riffle.core.models.TocEntry
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Property test for the rail's global readability invariant. Unlike the fixture tests, this is
 * not tied to any expected segment list, so it cannot be "updated to match" a behavior change —
 * weakening it requires editing this claim itself:
 *
 * Same-file section segments (several rail segments sharing one spine resource) may only appear
 * when the whole book stays within [MAX_SEGMENTS_WITH_SAME_FILE_SECTIONS]. Beyond that the rail's
 * 2.5dp inter-segment gaps dominate and the chapter map degenerates — the section-flood bug
 * reported on "A Philosophy of Software Design" / "Monolith to Microservices". Flat TOCs are
 * exempt: one segment per spine resource mirrors the book's real granularity at any count.
 */
class RailSegmentInvariantTest {

    @Test
    fun `same-file sections are never exposed beyond the readability cap`() {
        val rng = Random(42)
        repeat(500) { iteration ->
            val toc = randomToc(rng)
            val spine = toc.map { it.href.substringBefore('#') }
            val segments = if (rng.nextBoolean()) {
                buildRailSegments(toc)
            } else {
                buildRailSegments(
                    toc,
                    bookTitle = "Property Book",
                    spineHrefs = spine,
                    positionCounts = spine.map { rng.nextInt(0, 40) },
                )
            }
            val hasSameFileSections = segments
                .groupBy { it.href.substringBefore('#') }
                .any { it.value.size > 1 }
            if (hasSameFileSections) {
                assertTrue(
                    segments.size <= MAX_SEGMENTS_WITH_SAME_FILE_SECTIONS,
                    "iteration $iteration: ${segments.size} segments while same-file sections " +
                        "are exposed (cap $MAX_SEGMENTS_WITH_SAME_FILE_SECTIONS)",
                )
            }
        }
    }

    /**
     * Random book shapes spanning the generator's decision space: flat leaves, chapters with
     * same-file section anchors, cross-file part containers with chapter children, and the
     * occasional blank-titled wrapper.
     */
    private fun randomToc(rng: Random): List<TocEntry> {
        val topLevelCount = rng.nextInt(1, 41)
        return List(topLevelCount) { i ->
            val href = "ch$i.xhtml"
            when (rng.nextInt(4)) {
                // Flat leaf chapter.
                0 -> TocEntry("Chapter $i", href)
                // Chapter with same-file section anchors.
                1 -> TocEntry(
                    "Chapter $i",
                    href,
                    List(rng.nextInt(1, 13)) { s -> TocEntry("$i.$s", "$href#s$s") },
                )
                // Cross-file part container with leaf chapter children.
                2 -> TocEntry(
                    "Part $i",
                    href,
                    List(rng.nextInt(1, 7)) { c -> TocEntry("Chapter $i.$c", "ch$i-$c.xhtml") },
                )
                // Blank-titled wrapper (always replaced by its children).
                else -> TocEntry(
                    if (rng.nextBoolean()) "" else "Chapter $i",
                    href,
                    List(rng.nextInt(1, 5)) { s -> TocEntry("$i.$s", "$href#s$s") },
                )
            }
        }
    }
}

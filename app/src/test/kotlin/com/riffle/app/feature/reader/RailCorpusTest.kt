package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden tests over real, full-scale book TOCs (the JSON fixtures in
 * `src/test/resources/railcorpus/`).
 *
 * The rail heuristics in [buildRailSegments] have repeatedly been recalibrated against whichever
 * single book was at hand (#566, #656, the section-flood fix), and each recalibration risks
 * silently changing every other book's map. The miniature fixtures in [RailSegmentGeneratorTest]
 * can't show that blast radius — the section-flood regression shipped precisely because the
 * decision to expose same-file sections was made against a 2-chapter fixture. Each corpus book
 * pins the full rendered segment list at real scale, so any heuristic change surfaces here as a
 * reviewable per-book diff instead of an on-device surprise.
 *
 * When a corpus test fails after a heuristic change, do NOT mechanically regenerate `expected`:
 * the diff is the decision. Update it only when the new rail granularity for that book is the
 * intended outcome, and say so in the PR body. When a new book renders badly in the wild, add its
 * TOC as a fixture (extract from the real EPUB where possible; each fixture's `provenance` field
 * records how faithful it is).
 */
class RailCorpusTest {

    @Test
    fun `a philosophy of software design`() = assertCorpus("a-philosophy-of-software-design")

    @Test
    fun `monolith to microservices`() = assertCorpus("monolith-to-microservices")

    private fun assertCorpus(name: String) {
        val corpus = loadCorpusBook(name)
        val segments = buildRailSegments(
            tocEntries = corpus.toc.map { it.toModel() },
            bookTitle = corpus.book,
            spineHrefs = corpus.spine.map { it.href },
            positionCounts = corpus.spine.map { it.positions },
        )
        assertEquals(
            corpus.expected.map { RailSegment(it.title, it.href, groupIndex = it.groupIndex) },
            segments,
        )
    }

    private fun loadCorpusBook(name: String): CorpusBook {
        val resource = requireNotNull(javaClass.getResourceAsStream("/railcorpus/$name.json")) {
            "missing corpus fixture /railcorpus/$name.json"
        }
        return json.decodeFromString(resource.bufferedReader().readText())
    }

    @Serializable
    private data class CorpusBook(
        val book: String,
        val provenance: String,
        val toc: List<CorpusToc>,
        val spine: List<CorpusSpineEntry>,
        val expected: List<CorpusSegment>,
    )

    @Serializable
    private data class CorpusToc(
        val title: String,
        val href: String,
        val children: List<CorpusToc> = emptyList(),
    ) {
        fun toModel(): TocEntry = TocEntry(title, href, children.map { it.toModel() })
    }

    @Serializable
    private data class CorpusSpineEntry(val href: String, val positions: Int)

    @Serializable
    private data class CorpusSegment(val title: String, val href: String, val groupIndex: Int? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

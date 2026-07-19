package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry

/**
 * Simple, JVM-testable view of one PDF outline node. The Riffle reader's
 * ViewModel walks Readium's `PdfDocument.OutlineNode` tree once at book-open
 * and projects it to this shape, then hands the result to
 * [pdfOutlineToTocEntries] and [pdfOutlineToFlatRailEntries].
 *
 * Kept Readium-free so the conversion logic can be unit-tested without
 * spinning up a real PDF.
 *
 * @property title  Chapter / section title as the PDF outline names it.
 *   Empty strings are allowed (Acrobat sometimes emits anonymous group
 *   nodes); the TocPanel auto-hides those by descending into children.
 * @property pageIndex  0-based page index where this entry begins. Outline
 *   entries that don't resolve to a page (e.g. JS / form destinations) are
 *   filtered out by the caller before this projection.
 * @property children  Nested sub-entries.
 */
data class PdfOutlineNode(
    val title: String,
    val pageIndex: Int,
    val children: List<PdfOutlineNode> = emptyList(),
)

/**
 * Project a PDF outline tree into the same [TocEntry] shape EPUB uses.
 * The existing [TocPanel] is book-format-agnostic — it operates on
 * `(title, href, children)` and treats the href as an opaque identifier.
 * For PDF the href is a synthetic `page=N` token; nothing parses it
 * except [pdfActiveHref] (which generates the matching string from the
 * current page index when computing the active row).
 */
fun pdfOutlineToTocEntries(outline: List<PdfOutlineNode>): List<TocEntry> =
    outline.map { node ->
        TocEntry(
            title = node.title,
            href = pdfSegmentHref(node.pageIndex),
            children = pdfOutlineToTocEntries(node.children),
        )
    }

/**
 * Project the outline to the entries the chapter-navigation rail consumes.
 *
 * PDFs often have deeply-nested outlines — a typical textbook can have
 * 80+ entries when sub-sections are included, which makes the rail
 * visually useless: the ChapterNavigationRail draws a 2.5 dp gap between
 * segments, so 80 segments consume more horizontal pixels in gaps than in
 * actual ticks (96 × 2.5 dp ≈ 720 px out of a 1080 px rail). The rail
 * collapses to a few wide bars while most segments shrink below the visible
 * threshold.
 *
 * Instead, the rail uses only the **top-level outline nodes** — the
 * book's "real chapters" — at most a couple dozen entries. The TOC drawer
 * still shows the full tree (via [pdfOutlineToTocEntries]) so the user can
 * jump to any sub-section. If the top-level layer is empty (an outline
 * with only a single hidden grouping node), descend one level and use
 * those — better than rendering nothing.
 */
fun pdfOutlineToFlatRailEntries(outline: List<PdfOutlineNode>): List<PdfTocEntry> {
    val topLevel = outline
        .filter { it.title.isNotBlank() }
        .map { PdfTocEntry(title = it.title, pageIndex = it.pageIndex) }
    if (topLevel.isNotEmpty()) return topLevel
    // Fallback: every top-level node is anonymous (Acrobat sometimes wraps
    // the whole outline in a single anonymous root). Descend.
    return outline
        .flatMap { it.children }
        .filter { it.title.isNotBlank() }
        .map { PdfTocEntry(title = it.title, pageIndex = it.pageIndex) }
}

/**
 * The synthetic href the TocPanel matches against to highlight the active
 * row, computed from the current page index. Matches the href format
 * [pdfOutlineToTocEntries] emits.
 *
 * Note: TocPanel's active match is exact-string. To highlight "the row
 * whose chapter contains the current page" rather than "the row whose page
 * is exactly the current page", the caller should compute the active row's
 * page index (via the same logic [findActivePdfSegmentIndex] uses) and
 * pass its synthetic href here.
 */
fun pdfActiveHref(pageIndex: Int): String = pdfSegmentHref(pageIndex)

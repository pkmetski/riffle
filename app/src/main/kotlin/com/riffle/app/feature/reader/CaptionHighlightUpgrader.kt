package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.EmbeddedFigure
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Opportunistically upgrades legacy `TYPE_IMAGE` annotations (figure long-presses whose caption
 * used to be stuffed into `textSnippet` without a locator) into proper `TYPE_HIGHLIGHT`
 * annotations covering the caption text with the figure carried as an `embeddedFigure`. Runs
 * once per (VM lifecycle, book) from the reader's open path, per the 2026-07-14 design.
 *
 * The upgrade mirrors [FigureCaptionWalker.CAPTION_RESOLVER_JS] on the Kotlin side: for each
 * legacy annotation, parse its chapter HTML with jsoup, locate the figure by `img[src$="…"]`
 * filename match (or by inline-SVG prefix), walk up to the nearest `<figure>` for its
 * `<figcaption>`, and if that fails hunt for the nearest following `<p>`/`<div>` whose text
 * starts with `Figure N` / `Fig. N` / `Table N` / `Chart N` — the same content-anchored
 * fallback used at create time for LaTeX/Kotobee/Vellum EPUBs without semantic figure markup.
 *
 * Fuzzy caption-text match: an annotation is upgraded only when the DOM-resolved caption's
 * normalized text starts with the annotation's stored `textSnippet` (also normalized). This
 * catches the common case where `textSnippet` was truncated / equal to the caption, and
 * refuses to upgrade when the DOM has drifted (publisher republished the EPUB with a different
 * caption). A refused upgrade leaves the annotation as legacy TYPE_IMAGE — the reader keeps
 * showing the CSS caption tint until the next reopen tries again.
 *
 * The upgrade preserves the annotation's id so cross-device sync sees a normal field update
 * (bumped `updatedAt` + provenance), not a delete/create dance.
 */
internal class CaptionHighlightUpgrader(
    private val annotationStore: AnnotationStore,
) {

    /**
     * Sweep result — counts for logging.
     */
    internal data class SweepResult(val merged: Int, val upgraded: Int) {
        val total: Int get() = merged + upgraded
    }

    /**
     * Two phases, in order:
     *
     *  1. **Duplicate-caption cleanup.** Group same-chapter `TYPE_HIGHLIGHT` rows by normalized
     *     `textSnippet`. For each group with more than one row, pick a canonical (highest
     *     `updatedAt`; tiebreaker: has at least one `embeddedFigure`), merge every other row's
     *     figures into it via [AnnotationStore.mergeFiguresIntoHighlight], and tombstone the
     *     others. Fixes the "two HIGHLIGHTs on the same caption" duplicates the initial
     *     2026-07-14 change could leave behind on a re-long-press of the same figure.
     *
     *  2. **Legacy TYPE_IMAGE upgrade.** For each legacy `TYPE_IMAGE`, resolve the figure and
     *     caption in the chapter DOM. If a same-chapter HIGHLIGHT already covers the caption
     *     text (by normalized-snippet equality), merge the figure into that HIGHLIGHT and
     *     tombstone the TYPE_IMAGE — mirrors [EpubReaderViewModel.onFigureLongPress]'s dedup
     *     path so a legacy figure + a pre-existing text-selection highlight of the caption
     *     converge to one annotation. Otherwise rewrite the TYPE_IMAGE in place via
     *     [AnnotationStore.upgradeImageToCaptionHighlight].
     */
    suspend fun sweep(
        annotations: List<Annotation>,
        readChapterHtml: suspend (spineIndex: Int) -> String?,
    ): SweepResult {
        val merged = mergeDuplicateCaptionHighlights(annotations)
        // Re-read the annotation set from the caller's snapshot; anything the merge phase
        // tombstoned needs to be dropped so the upgrade phase doesn't touch a deleted row.
        val liveIdsAfterMerge = annotations
            .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT || it.type == AnnotationEntity.TYPE_IMAGE }
            .filter { it.id !in tombstonedIds }
            .map { it.id }
            .toSet()
        val liveAnnotations = annotations.filter { it.id in liveIdsAfterMerge }
        val upgraded = upgradeLegacyImageAnnotations(liveAnnotations, readChapterHtml)
        tombstonedIds.clear()
        return SweepResult(merged = merged, upgraded = upgraded)
    }

    private val tombstonedIds = mutableSetOf<String>()

    /**
     * Runs only the duplicate-cleanup phase — no source HTML needed, so this fires from the
     * Highlights-mode reader open too (where `lifecycle.publication` is never bound and the full
     * [sweep] can't do the legacy-upgrade phase). Fixes the "captions still doubled" symptom that
     * shows when the user opens a book via the Annotations tab without first opening it in
     * FullBook mode: the legacy upgrade may have already happened in a prior FullBook open, but
     * a later re-long-press-in-FullBook created a duplicate HIGHLIGHT, and until the user next
     * opens the book in FullBook again the duplicates never got merged. Running cleanup here
     * closes that gap.
     */
    suspend fun cleanupDuplicatesOnly(annotations: List<Annotation>): Int =
        mergeDuplicateCaptionHighlights(annotations).also { tombstonedIds.clear() }

    private suspend fun mergeDuplicateCaptionHighlights(annotations: List<Annotation>): Int {
        val groups = annotations
            .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT && it.textSnippet.isNotBlank() }
            .groupBy { normalizeCaptionText(it.chapterHref) to normalizeCaptionText(it.textSnippet) }
            .filterValues { it.size > 1 }
        var mergedCount = 0
        for ((_, rows) in groups) {
            val canonical = rows.maxWithOrNull(
                compareBy<Annotation>(
                    { (it.embeddedFigures?.size ?: 0) > 0 },
                    { it.updatedAt },
                ),
            ) ?: continue
            val extras = rows.filter { it.id != canonical.id }
            if (extras.isEmpty()) continue
            val allFigures = extras.flatMap { it.embeddedFigures.orEmpty() }
            if (allFigures.isNotEmpty()) {
                runCatching { annotationStore.mergeFiguresIntoHighlight(canonical.id, allFigures) }
            }
            for (extra in extras) {
                runCatching { annotationStore.delete(extra.id) }
                tombstonedIds += extra.id
                mergedCount++
            }
        }
        return mergedCount
    }

    /**
     * Attempt to upgrade every legacy `TYPE_IMAGE` annotation in [annotations] whose figure and
     * caption can both be resolved against the current publication. [readChapterHtml] returns
     * the raw XHTML for a given spine index (may cache), null when unavailable. Returns the
     * count of upgraded annotations — used for logging and to fire a sync when non-zero.
     *
     * When a same-chapter `TYPE_HIGHLIGHT` already covers the caption text (normalized snippet
     * equality), the legacy row's figure is merged into that HIGHLIGHT and the legacy row is
     * tombstoned — a caption-highlight and a legacy image-annotation of the same figure
     * converge to one annotation, matching [EpubReaderViewModel.onFigureLongPress]'s dedup.
     */
    suspend fun upgradeLegacyImageAnnotations(
        annotations: List<Annotation>,
        readChapterHtml: suspend (spineIndex: Int) -> String?,
    ): Int {
        val legacy = annotations.filter { it.type == AnnotationEntity.TYPE_IMAGE }
        if (legacy.isEmpty()) return 0
        val highlightsByChapterCaption = annotations
            .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT && it.textSnippet.isNotBlank() }
            .groupBy { normalizeCaptionText(it.chapterHref) to normalizeCaptionText(it.textSnippet) }
            .mapValues { (_, rows) ->
                rows.maxByOrNull { (it.embeddedFigures?.size ?: 0) * 1_000_000_000L + it.updatedAt }
            }
        var upgraded = 0
        for (annotation in legacy) {
            val html = readChapterHtml(annotation.spineIndex) ?: continue
            val plan = planUpgrade(annotation, html) ?: continue
            val chapterKey = normalizeCaptionText(annotation.chapterHref)
            val captionKey = normalizeCaptionText(plan.captionText)
            val existingHighlight = highlightsByChapterCaption[chapterKey to captionKey]
            if (existingHighlight != null) {
                val result = runCatching {
                    annotationStore.mergeFiguresIntoHighlight(existingHighlight.id, listOf(plan.figure))
                }.getOrNull()
                if (result != null) {
                    runCatching { annotationStore.delete(annotation.id) }
                    upgraded++
                }
                continue
            }
            val result = runCatching {
                annotationStore.upgradeImageToCaptionHighlight(
                    id = annotation.id,
                    cfi = plan.cfi,
                    textSnippet = plan.captionText,
                    textBefore = plan.textBefore,
                    textAfter = plan.textAfter,
                    figure = plan.figure,
                )
            }.getOrNull()
            if (result != null) upgraded++
        }
        return upgraded
    }

    private fun planUpgrade(annotation: Annotation, html: String): UpgradePlan? {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val figureEl = findFigureElement(doc, annotation) ?: return null
        val captionEl = resolveCaptionElement(figureEl) ?: return null
        val captionText = normalizeWhitespace(captionEl.text())
        if (captionText.isBlank()) return null
        val storedSnippet = normalizeWhitespace(annotation.textSnippet)
        // Fuzzy match: stored snippet equals or is a prefix of the DOM caption. Guards against
        // upgrading when publisher HTML drifted (rewrapped, renumbered, or different caption).
        if (storedSnippet.isNotBlank() && !captionText.startsWith(storedSnippet, ignoreCase = true)) {
            return null
        }
        val textBefore = collectTextAround(doc.body(), captionEl, direction = -1, maxChars = 40)
        val textAfter = collectTextAround(doc.body(), captionEl, direction = +1, maxChars = 40)
        val startChar = locateSnippetInBody(html, captionText, textBefore) ?: return null
        val spineStep = (annotation.spineIndex + 1) * 2
        val cfiRange = buildHighlightCfiRange(
            spineStep = spineStep,
            html = html,
            startChar = startChar,
            endChar = (startChar + captionText.length - 1L).coerceAtLeast(startChar),
        ) ?: return null
        // caption="" — the upgraded HIGHLIGHT's textSnippet carries the caption; storing it on
        // the figure too would cause the elided view to render the caption text twice
        // (figure's own <figcaption> + outer <p> from the highlight's snippet chunk).
        val figure = EmbeddedFigure(
            href = annotation.imageHref,
            svg = annotation.imageSvg,
            caption = "",
            order = 0,
            imageBytes = annotation.imageBytes,
            charOffset = 0L,
        )
        return UpgradePlan(cfiRange, captionText, textBefore, textAfter, figure)
    }

    private fun findFigureElement(doc: org.jsoup.nodes.Document, annotation: Annotation): Element? {
        val href = annotation.imageHref
        if (href != null) {
            val filename = figureHrefFilename(href)
            // Escape filename for use inside a jsoup [attr$="…"] selector; wrap the value in
            // single quotes and escape any single quote in the filename itself. Jsoup accepts
            // both quote styles for attribute selectors.
            val safe = filename.replace("'", "\\'")
            doc.select("img[src\$='$safe']").firstOrNull()?.let { return it }
            doc.select("picture img[src\$='$safe']").firstOrNull()?.let { return it }
        }
        val svg = annotation.imageSvg
        if (svg != null) {
            val prefix = svg.take(200)
            for (el in doc.select("svg")) {
                if (el.outerHtml().startsWith(prefix)) return el
            }
        }
        return null
    }

    /**
     * Kotlin mirror of [FigureCaptionWalker]'s `resolveCaptionElement`. Prefers a `<figure>` /
     * `[role="figure"]` ancestor's `<figcaption>` child; falls back to the nearest following
     * `<p>`/`<div>` whose text starts with `Figure N` / `Fig. N` / `Table N` / `Chart N`
     * within 3 ancestor hops.
     */
    internal fun resolveCaptionElement(figureEl: Element): Element? {
        var scan: Element? = figureEl
        while (scan != null) {
            if (scan.tagName().equals("figure", ignoreCase = true) ||
                scan.attr("role").equals("figure", ignoreCase = true)
            ) {
                scan.select("figcaption").firstOrNull { it.text().isNotBlank() }?.let { return it }
                break
            }
            scan = scan.parent()
        }
        var cur: Element = figureEl
        for (hops in 0 until 3) {
            val parent = cur.parent() ?: break
            for (block in parent.select("p, div")) {
                if (block === figureEl || block.contains(figureEl)) continue
                if (documentOrderCompare(figureEl, block) >= 0) continue
                if (CAPTION_PREFIX_REGEX.containsMatchIn(block.text().trim())) return block
            }
            cur = parent
        }
        return null
    }

    /**
     * Returns > 0 when [b] appears after [a] in document order, < 0 when before, 0 when equal.
     * Uses jsoup's [Element.elementSiblingIndex] via a walk up the shared-ancestor chain.
     */
    private fun documentOrderCompare(a: Element, b: Element): Int {
        if (a === b) return 0
        val aAncestors = ancestorChain(a)
        val bAncestors = ancestorChain(b)
        // Walk from root downward until they diverge.
        var i = 0
        while (i < aAncestors.size && i < bAncestors.size && aAncestors[i] === bAncestors[i]) i++
        // Compare the diverging siblings.
        val aSibling = aAncestors.getOrNull(i) ?: return -1 // a is ancestor of b
        val bSibling = bAncestors.getOrNull(i) ?: return 1
        return aSibling.elementSiblingIndex() - bSibling.elementSiblingIndex()
    }

    private fun ancestorChain(el: Element): List<Element> {
        val out = mutableListOf<Element>()
        var cur: Element? = el
        while (cur != null) {
            out.add(0, cur)
            cur = cur.parent()
        }
        return out
    }

    private fun collectTextAround(
        root: Element?,
        capEl: Element,
        direction: Int,
        maxChars: Int,
    ): String {
        if (root == null || !root.contains(capEl)) return ""
        val out = StringBuilder()
        var found = false
        // Returns true to signal "stop walking" (either done collecting forward, or hit the
        // caption while collecting backward — since we've now seen everything before it).
        fun visit(node: Node): Boolean {
            if (node === capEl) {
                if (direction < 0) return true
                found = true
                return false
            }
            if (capEl.contains(node)) return false
            if (node is TextNode) {
                if (direction < 0 && !found) out.append(node.wholeText)
                else if (direction > 0 && found) {
                    out.append(node.wholeText)
                    if (out.length >= maxChars) return true
                }
                return false
            }
            for (child in node.childNodes()) if (visit(child)) return true
            return false
        }
        visit(root)
        val joined = normalizeWhitespace(out.toString())
        return if (direction < 0) joined.takeLast(maxChars) else joined.take(maxChars)
    }

    private fun normalizeWhitespace(s: String): String = s.replace(WHITESPACE_RUN, " ").trim()

    private data class UpgradePlan(
        val cfi: String,
        val captionText: String,
        val textBefore: String,
        val textAfter: String,
        val figure: EmbeddedFigure,
    )

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")
        val CAPTION_PREFIX_REGEX = Regex("^\\s*(Figure|Fig\\.?|Table|Chart)\\s+\\d", RegexOption.IGNORE_CASE)
    }
}

/**
 * Shared caption-identity normalizer used by both [CaptionHighlightUpgrader]'s dedup pass and
 * [EpubReaderViewModel.onFigureLongPress]'s dedup check — squashes internal whitespace runs so
 * different renderers of "the same" caption (`textSnippet` captured with newlines, jsoup
 * `text()` collapsed) both compare equal.
 */
internal fun normalizeCaptionText(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

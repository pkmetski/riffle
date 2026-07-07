package com.riffle.app.feature.reader

import com.riffle.core.domain.buildCfiDocPath
import com.riffle.core.domain.cfiDocPathToProgression
import com.riffle.core.domain.countBodyChars
import com.riffle.core.domain.extractCfiDocPath
import com.riffle.core.domain.findNodeAtChar
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// Builds an EPUB CFI *range* for a highlight (ADR 0024). Reuses the char-count positioning model
// of EpubCfiTranslator so the range's endpoints are in the same coordinate family ABS stores.
//
// A range CFI factors the two endpoints to their nearest common ancestor:
//   epubcfi(/6/<spineStep>!<commonParent>,<startRemainder>,<endRemainder>)
// e.g. epubcfi(/6/4!/4/2,/1:0,/1:5) — both ends inside the same text node of the first paragraph.

/**
 * Build a range CFI spanning [startChar]..[endChar] (body character offsets, blank-text-node aware)
 * within the chapter [html] at spine step [spineStep] (i.e. `/6/<spineStep>`). Returns null when
 * either offset falls outside the document.
 */
internal fun buildHighlightCfiRange(spineStep: Int, html: String, startChar: Long, endChar: Long): String? {
    val doc = Jsoup.parse(html)
    val htmlEl = doc.child(0)
    val body = doc.body()

    val (startNode, startOffset) = findNodeAtChar(body, startChar) ?: return null
    val (endNode, endOffset) = findNodeAtChar(body, endChar) ?: return null

    val startPath = buildCfiDocPath(htmlEl, startNode, startOffset) ?: return null
    val endPath = buildCfiDocPath(htmlEl, endNode, endOffset) ?: return null

    val startTokens = startPath.trimStart('/').split('/')
    val endTokens = endPath.trimStart('/').split('/')

    var common = 0
    while (common < startTokens.size && common < endTokens.size && startTokens[common] == endTokens[common]) {
        common++
    }

    val parent = "/" + startTokens.take(common).joinToString("/")
    val startRemainder = "/" + startTokens.drop(common).joinToString("/")
    val endRemainder = "/" + endTokens.drop(common).joinToString("/")

    return "epubcfi(/6/$spineStep!$parent,$startRemainder,$endRemainder)"
}

/**
 * Build a range CFI from a Readium selection's start [startProgression] and its [selectedText].
 * The end is the start advanced by the selected text's length in the char-count model — exact for
 * single-node selections and a close best-effort across nodes (the end point is not load-bearing;
 * the start point and text snippet re-anchor the highlight). Returns null for blank selections or
 * when the start falls outside the document.
 */
internal fun buildHighlightCfiRangeForSelection(
    spineStep: Int,
    html: String,
    startProgression: Double,
    selectedText: String,
): String? {
    if (selectedText.isBlank()) return null
    val doc = Jsoup.parse(html)
    val body = doc.body()
    val totalChars = countBodyChars(body)
    if (totalChars == 0L) return null

    val startChar = (startProgression.coerceIn(0.0, 1.0) * totalChars).toLong().coerceIn(0L, totalChars - 1L)
    val endChar = (startChar + selectedText.length).coerceAtMost(totalChars - 1L)
    return buildHighlightCfiRange(spineStep, html, startChar, endChar)
}

/**
 * The absolute doc path of a range CFI's start point — `<commonParent><startRemainder>` — suitable
 * for [cfiDocPathToProgression]. Returns null if [rangeCfi] is not a well-formed range.
 */
internal fun rangeStartDocPath(rangeCfi: String): String? {
    val docPath = extractCfiDocPath(rangeCfi) ?: return null
    val parts = docPath.split(',')
    if (parts.size != 3) return null
    val parent = parts[0].trimEnd('/')
    val startRemainder = parts[1]
    return parent + startRemainder
}

/**
 * The within-chapter progression of a stored highlight's start, used to re-anchor (re-render) the
 * highlight on reopen. Returns null when [rangeCfi] is not a range or its start can't be located in
 * [html].
 */
internal fun highlightStartProgression(rangeCfi: String, html: String): Double? {
    val startDocPath = rangeStartDocPath(rangeCfi) ?: return null
    return cfiDocPathToProgression(startDocPath, html)
}

/**
 * Concatenation of the body's readable-text (blank-only text nodes skipped) — the "flat" view of
 * the chapter that [countBodyChars] measures. All char offsets used by highlight merging are
 * indices into this string.
 */
internal fun readableBodyText(html: String): String {
    val body = Jsoup.parse(html).body()
    val out = StringBuilder()
    fun visit(node: Node) {
        when (node) {
            is TextNode -> if (!node.wholeText.isBlank()) out.append(node.wholeText)
            is Element -> node.childNodes().forEach(::visit)
        }
    }
    body.childNodes().forEach(::visit)
    return out.toString()
}

/**
 * Locate the char position of [snippet] in [html]'s readable-text stream, using [before]'s last
 * ~30 chars as a disambiguating anchor. Returns the start-char of [snippet] in the readable
 * stream, or null if not locatable / ambiguous.
 *
 * Why we need this: `Locator.locations.progression` from a paginated Readium selection is the
 * *page*'s progression, not the selection's. Every highlight created on the same page gets the
 * same stored progression and CFI start offset, so the persisted char-position is useless for
 * pinning down the selection's true location. Text-searching the DOM with the surrounding-text
 * anchor recovers the real position.
 */
internal fun locateSnippetInBody(
    html: String,
    snippet: String,
    before: String,
): Long? {
    if (snippet.isBlank()) return null
    val body = readableBodyText(html)
    val anchorTail = before.takeLast(30).trimStart()
    if (anchorTail.isNotEmpty()) {
        val query = anchorTail + snippet
        val idx = body.indexOf(query)
        if (idx >= 0) return (idx + anchorTail.length).toLong()
    }
    // Anchor-less fallback: only trustworthy if the snippet appears exactly once in the chapter.
    val first = body.indexOf(snippet)
    if (first < 0) return null
    if (body.indexOf(snippet, first + 1) >= 0) return null
    return first.toLong()
}

/**
 * Extract the readable-text substring of [html]'s body between [startChar] (inclusive) and
 * [endChar] (exclusive) — using the same "readable char" walk as [countBodyChars] (blank-only
 * text nodes skipped). Returns null if the range is empty or falls outside the doc.
 *
 * Used by the highlight-merge path so the stored merged `textSnippet` is byte-exact against the
 * DOM. Concatenating the two source snippets with a captured whitespace run can drift from the
 * actual DOM (Readium's `textAfter`/`textBefore` may normalise NBSP/newlines differently than
 * `textSnippet`), and any drift makes Readium's decorator fail-then-fallback to a partial range.
 */
internal fun readableTextBetween(html: String, startChar: Long, endChar: Long): String? {
    if (endChar <= startChar) return null
    val body = Jsoup.parse(html).body()
    val out = StringBuilder()
    var cursor = 0L

    fun visit(node: Node): Boolean {
        if (cursor >= endChar) return true
        when (node) {
            is TextNode -> {
                val text = node.wholeText
                if (text.isBlank()) return false
                val nodeStart = cursor
                val nodeLen = text.length.toLong()
                val nodeEnd = nodeStart + nodeLen
                if (nodeEnd > startChar) {
                    val takeFrom = maxOf(0L, startChar - nodeStart).toInt()
                    val takeTo = minOf(nodeLen, endChar - nodeStart).toInt()
                    out.append(text, takeFrom, takeTo)
                }
                cursor = nodeEnd
            }
            is Element -> {
                for (child in node.childNodes()) {
                    if (visit(child)) return true
                }
            }
        }
        return cursor >= endChar
    }
    for (child in body.childNodes()) {
        if (visit(child)) break
    }
    return out.toString().takeIf { it.isNotEmpty() }
}

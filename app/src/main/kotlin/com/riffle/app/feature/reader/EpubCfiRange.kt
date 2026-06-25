package com.riffle.app.feature.reader

import org.jsoup.Jsoup

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

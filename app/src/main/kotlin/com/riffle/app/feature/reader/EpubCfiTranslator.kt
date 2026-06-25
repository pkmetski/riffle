package com.riffle.app.feature.reader

import com.riffle.core.domain.EpubTextChars
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// epub.js↔Readium CFI translation via character-count positions. See ADR 0013.

// ── Public API ───────────────────────────────────────────────────────────────


internal fun extractCfiDocPath(fullCfi: String): String? {
    if (!fullCfi.startsWith("epubcfi(") || !fullCfi.endsWith(")")) return null
    val inner = fullCfi.removePrefix("epubcfi(").removeSuffix(")")
    val bang = inner.indexOf('!')
    if (bang < 0) return null
    return inner.substring(bang + 1).takeIf { it.isNotEmpty() }
}

internal fun cfiDocPathToProgression(docPath: String, html: String): Double? {
    val doc = Jsoup.parse(html)
    val htmlEl = doc.child(0)
    val body = doc.body()

    val totalChars = countBodyChars(body)
    if (totalChars == 0L) return null

    // ID-anchored first (image-resistant); falls back to pure numeric step walk
    val anchored = cfiDocPathToProgressionIdAnchored(docPath, doc, body, totalChars)
    if (anchored != null) return anchored

    // Fall back to pure numeric step walk
    val parsed = parseCfiDocPath(docPath) ?: return null
    val targetNode = walkCfiSteps(htmlEl, parsed.steps) as? TextNode ?: return null
    val charsBefore = countCharsBefore(body, targetNode, parsed.charOffset)
    if (charsBefore < 0L) return null
    return (charsBefore.toDouble() / totalChars).coerceIn(0.0, 1.0)
}

internal fun progressionToCfiDocPath(progression: Double, html: String): String? {
    val doc = Jsoup.parse(html)
    val htmlEl = doc.child(0)
    val body = doc.body()

    val totalChars = countBodyChars(body)
    if (totalChars == 0L) return null

    val targetChar = (progression.coerceIn(0.0, 1.0) * totalChars)
        .toLong().coerceIn(0L, totalChars - 1L)

    val (textNode, offset) = findNodeAtChar(body, targetChar) ?: return null
    return buildCfiDocPath(htmlEl, textNode, offset)
}

internal fun extractCfiElementIds(docPath: String): List<String> {
    val parts = docPath.trimStart('/').split('/').filter { it.isNotEmpty() }
    val ids = mutableListOf<String>()
    for (part in parts) {
        val noAssertion = part.substringBefore('[')
        val stepNum = noAssertion.substringBefore(':').toIntOrNull() ?: continue
        if (stepNum % 2 == 0 && '[' in part) {
            val id = part.substringAfter('[').substringBefore(']')
            if (id.isNotEmpty()) ids.add(id)
        }
    }
    return ids.reversed()
}

internal fun hasElementWithId(html: String, id: String): Boolean =
    Jsoup.parse(html).getElementById(id) != null

/**
 * Returns the innermost element ID from the doc-path portion of [fullCfi] that actually
 * exists in [html], or null when the CFI has no ID assertions or none match the DOM.
 * Used by continuous-mode navigation to anchor on the exact element rather than a
 * character-count progression approximation.
 */
internal fun extractAnchorFromCfi(fullCfi: String, html: String): String? {
    val docPath = extractCfiDocPath(fullCfi) ?: return null
    return extractCfiElementIds(docPath).firstOrNull { hasElementWithId(html, it) }
}

// ── CFI string parsing ────────────────────────────────────────────────────────

internal data class ParsedCfiDocPath(val steps: List<Int>, val charOffset: Int)

internal fun parseCfiDocPath(docPath: String): ParsedCfiDocPath? {
    val parts = docPath.trimStart('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null

    val steps = mutableListOf<Int>()
    var charOffset = 0

    for ((i, part) in parts.withIndex()) {
        val noAssertion = part.substringBefore('[')
        if (i == parts.lastIndex && ':' in noAssertion) {
            val colon = noAssertion.indexOf(':')
            steps.add(noAssertion.substring(0, colon).toIntOrNull() ?: return null)
            charOffset = noAssertion.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            steps.add(noAssertion.toIntOrNull() ?: return null)
        }
    }
    return if (steps.isEmpty()) null else ParsedCfiDocPath(steps, charOffset)
}

// ── DOM walking ───────────────────────────────────────────────────────────────

// even step N → element child at index N/2-1; odd step N → non-blank text node at (N-1)/2
internal fun walkCfiSteps(root: Element, steps: List<Int>): Node? {
    var current: Node = root
    for (step in steps) {
        val parent = current as? Element ?: return null
        current = if (step % 2 == 0) {
            parent.children().getOrNull(step / 2 - 1) ?: return null
        } else {
            parent.childNodes()
                .filterIsInstance<TextNode>()
                .filter { it.wholeText.isNotBlank() }
                .getOrNull((step - 1) / 2) ?: return null
        }
    }
    return current
}

// ── Character counting ────────────────────────────────────────────────────────

// Single source of truth for the readable-character definition, shared with the
// cross-EPUB index (ADR 0019) via core/domain.
internal fun countBodyChars(body: Element): Long = EpubTextChars.countReadableChars(body)

internal fun countCharsBefore(body: Element, target: TextNode, offsetInTarget: Int): Long {
    var count = 0L
    var found = false

    fun visit(node: Node) {
        if (found) return
        when {
            node === target -> { count += offsetInTarget; found = true }
            node is TextNode && node.wholeText.isNotBlank() -> count += node.wholeText.length
            node is Element -> node.childNodes().forEach { visit(it) }
        }
    }

    body.childNodes().forEach { visit(it) }
    return if (found) count else -1L
}

// ── Finding node at character position ───────────────────────────────────────

internal fun findNodeAtChar(body: Element, targetChar: Long): Pair<TextNode, Int>? {
    var remaining = targetChar

    fun visit(node: Node): Pair<TextNode, Int>? = when (node) {
        is TextNode -> {
            if (node.wholeText.isBlank()) null
            else {
                val len = node.wholeText.length
                if (remaining < len) node to remaining.toInt()
                else { remaining -= len; null }
            }
        }
        is Element -> node.childNodes().firstNotNullOfOrNull { visit(it) }
        else -> null
    }

    return body.childNodes().firstNotNullOfOrNull { visit(it) }
}

// ── CFI path building ─────────────────────────────────────────────────────────

internal fun buildCfiDocPath(htmlEl: Element, textNode: TextNode, offset: Int): String? {
    val textParent = textNode.parentNode() as? Element ?: return null

    // Odd step for the text node: count non-whitespace text siblings before it
    val textStep = run {
        val nonBlankSiblings = textParent.childNodes()
            .filterIsInstance<TextNode>()
            .filter { it.wholeText.isNotBlank() }
        val idx = nonBlankSiblings.indexOf(textNode)
        if (idx < 0) return null
        idx * 2 + 1
    }

    // Walk up from textParent to htmlEl, building even steps in reverse.
    // Include the element's `id` as an assertion when present so recipients can
    // use ID-first navigation (e.g. epub.js, and our own inbound path).
    val elementStepStrings = mutableListOf<String>()
    var current: Element = textParent
    while (current !== htmlEl) {
        val parent = current.parentNode() as? Element ?: return null
        val idx = parent.children().indexOf(current)
        if (idx < 0) return null
        val stepNum = (idx + 1) * 2
        val id = current.id().takeIf { it.isNotEmpty() }
        elementStepStrings.add(0, if (id != null) "$stepNum[$id]" else "$stepNum")
        current = parent
    }

    return "/${(elementStepStrings + textStep.toString()).joinToString("/")}:$offset"
}

// ── ID-anchored navigation (private) ─────────────────────────────────────────

private fun countCharsBeforeElement(body: Element, target: Element): Long {
    var count = 0L
    var found = false

    fun visit(node: Node) {
        if (found) return
        when {
            node === target -> found = true
            node is TextNode && node.wholeText.isNotBlank() -> count += node.wholeText.length
            node is Element -> node.childNodes().forEach { visit(it) }
        }
    }

    body.childNodes().forEach { visit(it) }
    return if (found) count else -1L
}

// ID-first: finds deepest even-step assertion whose id exists in the doc; robust against image-heavy chapters
private fun cfiDocPathToProgressionIdAnchored(
    docPath: String,
    doc: org.jsoup.nodes.Document,
    body: Element,
    totalChars: Long,
): Double? {
    val parts = docPath.trimStart('/').split('/').filter { it.isNotEmpty() }

    var anchorElement: Element? = null
    var anchorPartIndex = -1
    for (i in parts.indices.reversed()) {
        val part = parts[i]
        val noAssertion = part.substringBefore('[')
        val stepNum = noAssertion.substringBefore(':').toIntOrNull() ?: continue
        if (stepNum % 2 == 0 && '[' in part) {
            val id = part.substringAfter('[').substringBefore(']')
            val el = if (id.isNotEmpty()) doc.getElementById(id) else null
            if (el != null) {
                anchorElement = el
                anchorPartIndex = i
                break
            }
        }
    }

    val anchor = anchorElement ?: return null

    val charsBeforeAnchor = countCharsBeforeElement(body, anchor)
    if (charsBeforeAnchor < 0L) return null

    val remainingParts = parts.drop(anchorPartIndex + 1)
    if (remainingParts.isEmpty()) {
        return (charsBeforeAnchor.toDouble() / totalChars).coerceIn(0.0, 1.0)
    }

    val remainingPath = "/" + remainingParts.joinToString("/")
    val remainingParsed = parseCfiDocPath(remainingPath) ?: return null
    val targetNode = walkCfiSteps(anchor, remainingParsed.steps) as? TextNode ?: return null
    val charsWithinAnchor = countCharsBefore(anchor, targetNode, remainingParsed.charOffset)
    if (charsWithinAnchor < 0L) return null

    return ((charsBeforeAnchor + charsWithinAnchor).toDouble() / totalChars).coerceIn(0.0, 1.0)
}

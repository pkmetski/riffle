package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Multiplatform CFI translator backed by ksoup (ADR 0013). Replaces the parallel
 * jvmMain/iosMain copies — the DOM-walking algorithm is identical across platforms; only the
 * parser import changed (org.jsoup → ksoup). jvmTest and iosTest suites can now merge into
 * commonTest against this single implementation.
 *
 * See the removed jvmMain/EpubCfiTranslator.kt for the inline documentation of the algorithm.
 */
internal fun ksoupExtractCfiDocPath(fullCfi: String): String? {
    if (!fullCfi.startsWith("epubcfi(") || !fullCfi.endsWith(")")) return null
    val inner = fullCfi.removePrefix("epubcfi(").removeSuffix(")")
    val bang = inner.indexOf('!')
    if (bang < 0) return null
    return inner.substring(bang + 1).takeIf { it.isNotEmpty() }
}

internal fun ksoupCfiDocPathToProgression(docPath: String, html: String): Double? {
    val doc = Ksoup.parse(html)
    val body = doc.body()
    val totalChars = ksoupCountBodyChars(body)
    if (totalChars == 0L) return null

    val anchored = ksoupCfiDocPathToProgressionIdAnchored(docPath, doc, body, totalChars)
    if (anchored != null) return anchored

    val htmlEl = doc.child(0)
    val parsed = ksoupParseCfiDocPath(docPath) ?: return null
    val targetNode = ksoupWalkCfiSteps(htmlEl, parsed.steps) as? TextNode ?: return null
    val charsBefore = ksoupCountCharsBefore(body, targetNode, parsed.charOffset)
    if (charsBefore < 0L) return null
    return (charsBefore.toDouble() / totalChars).coerceIn(0.0, 1.0)
}

internal fun ksoupProgressionToCfiDocPath(progression: Double, html: String): String? {
    val doc = Ksoup.parse(html)
    val htmlEl = doc.child(0)
    val body = doc.body()

    val totalChars = ksoupCountBodyChars(body)
    if (totalChars == 0L) return null

    val targetChar = (progression.coerceIn(0.0, 1.0) * totalChars)
        .toLong().coerceIn(0L, totalChars - 1L)

    val (textNode, offset) = ksoupFindNodeAtChar(body, targetChar) ?: return null
    return ksoupBuildCfiDocPath(htmlEl, textNode, offset)
}

// ── CFI string parsing ────────────────────────────────────────────────────────

internal data class KsoupParsedCfiDocPath(val steps: List<Int>, val charOffset: Int)

internal fun ksoupParseCfiDocPath(docPath: String): KsoupParsedCfiDocPath? {
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
    return if (steps.isEmpty()) null else KsoupParsedCfiDocPath(steps, charOffset)
}

// ── DOM walking ───────────────────────────────────────────────────────────────

internal fun ksoupWalkCfiSteps(root: Element, steps: List<Int>): Node? {
    var current: Node = root
    for (step in steps) {
        val parent = current as? Element ?: return null
        current = if (step % 2 == 0) {
            parent.children().getOrNull(step / 2 - 1) ?: return null
        } else {
            parent.childNodes()
                .filterIsInstance<TextNode>()
                .filter { it.getWholeText().isNotBlank() }
                .getOrNull((step - 1) / 2) ?: return null
        }
    }
    return current
}

// ── Character counting ────────────────────────────────────────────────────────

internal fun ksoupCountBodyChars(body: Element): Long {
    var total = 0L
    fun visit(node: Node) {
        when (node) {
            is TextNode -> if (node.getWholeText().isNotBlank()) total += node.getWholeText().length
            is Element -> node.childNodes().forEach { visit(it) }
        }
    }
    body.childNodes().forEach { visit(it) }
    return total
}

internal fun ksoupCountCharsBefore(body: Element, target: TextNode, offsetInTarget: Int): Long {
    var count = 0L
    var found = false

    fun visit(node: Node) {
        if (found) return
        when {
            node === target -> { count += offsetInTarget; found = true }
            node is TextNode && node.getWholeText().isNotBlank() -> count += node.getWholeText().length
            node is Element -> node.childNodes().forEach { visit(it) }
        }
    }

    body.childNodes().forEach { visit(it) }
    return if (found) count else -1L
}

// ── Finding node at character position ───────────────────────────────────────

internal fun ksoupFindNodeAtChar(body: Element, targetChar: Long): Pair<TextNode, Int>? {
    var remaining = targetChar

    fun visit(node: Node): Pair<TextNode, Int>? = when (node) {
        is TextNode -> {
            if (node.getWholeText().isBlank()) null
            else {
                val len = node.getWholeText().length
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

internal fun ksoupBuildCfiDocPath(htmlEl: Element, textNode: TextNode, offset: Int): String? {
    val textParent = textNode.parentNode() as? Element ?: return null

    val textStep = run {
        val nonBlankSiblings = textParent.childNodes()
            .filterIsInstance<TextNode>()
            .filter { it.getWholeText().isNotBlank() }
        val idx = nonBlankSiblings.indexOf(textNode)
        if (idx < 0) return null
        idx * 2 + 1
    }

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

private fun ksoupCountCharsBeforeElement(body: Element, target: Element): Long {
    var count = 0L
    var found = false

    fun visit(node: Node) {
        if (found) return
        when {
            node === target -> found = true
            node is TextNode && node.getWholeText().isNotBlank() -> count += node.getWholeText().length
            node is Element -> node.childNodes().forEach { visit(it) }
        }
    }

    body.childNodes().forEach { visit(it) }
    return if (found) count else -1L
}

private fun ksoupCfiDocPathToProgressionIdAnchored(
    docPath: String,
    doc: Document,
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

    val charsBeforeAnchor = ksoupCountCharsBeforeElement(body, anchor)
    if (charsBeforeAnchor < 0L) return null

    val remainingParts = parts.drop(anchorPartIndex + 1)
    if (remainingParts.isEmpty()) {
        return (charsBeforeAnchor.toDouble() / totalChars).coerceIn(0.0, 1.0)
    }

    val remainingPath = "/" + remainingParts.joinToString("/")
    val remainingParsed = ksoupParseCfiDocPath(remainingPath) ?: return null
    val targetNode = ksoupWalkCfiSteps(anchor, remainingParsed.steps) as? TextNode ?: return null
    val charsWithinAnchor = ksoupCountCharsBefore(anchor, targetNode, remainingParsed.charOffset)
    if (charsWithinAnchor < 0L) return null

    return ((charsBeforeAnchor + charsWithinAnchor).toDouble() / totalChars).coerceIn(0.0, 1.0)
}

package com.riffle.core.domain

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * The readable-character counting primitive shared by the CFI translator (ADR 0013)
 * and the cross-EPUB index (ADR 0019). "Readable" means non-blank text-node content:
 * markup, whitespace-only nodes, and image elements contribute zero, so the same
 * logical prose counts the same across two EPUBs that mark it up differently.
 */
object EpubTextChars {

    /** Count readable characters in a parsed body (or any element subtree). */
    fun countReadableChars(node: Node): Long = when (node) {
        is TextNode -> if (node.wholeText.isNotBlank()) node.wholeText.length.toLong() else 0L
        is Element -> node.childNodes().sumOf { countReadableChars(it) }
        else -> 0L
    }

    /** Parse [html] and count the readable characters of its `<body>`. */
    fun countReadableChars(html: String): Long {
        val body = Jsoup.parse(html).body() ?: return 0L
        return countReadableChars(body)
    }

    /**
     * The within-chapter progression (0..1) of the start of the element with [elementId]
     * in [html]: readable characters before that element divided by the chapter total.
     * Returns `null` when the id is absent or the chapter has no readable characters.
     */
    fun progressionOfElementId(html: String, elementId: String): Double? {
        val doc = Jsoup.parse(html)
        val body = doc.body() ?: return null
        val target = doc.getElementById(elementId) ?: return null
        val total = countReadableChars(body)
        if (total == 0L) return null
        val before = countReadableCharsBefore(body, target)
        if (before < 0L) return null
        return (before.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /** Readable characters before [target] in document order, or -1 if not found. */
    private fun countReadableCharsBefore(body: Element, target: Element): Long {
        var count = 0L
        var found = false
        fun visit(node: Node) {
            if (found) return
            when {
                node === target -> found = true
                node is TextNode -> if (node.wholeText.isNotBlank()) count += node.wholeText.length
                node is Element -> node.childNodes().forEach { visit(it) }
            }
        }
        body.childNodes().forEach { visit(it) }
        return if (found) count else -1L
    }
}

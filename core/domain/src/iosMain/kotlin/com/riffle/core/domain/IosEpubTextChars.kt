package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * iOS port of jvmMain's [EpubTextChars] — the readable-character counting primitive shared by the
 * CFI translator (ADR 0013) and the cross-EPUB index (ADR 0023). "Readable" means non-blank
 * text-node content: markup, whitespace-only nodes and image elements contribute zero, so the same
 * logical prose counts the same across two EPUBs that mark it up differently.
 *
 * Algorithm is unchanged from the JVM version; only the DOM library differs (ksoup for
 * Kotlin/Native, as org.jsoup has no K/N target — see IosEpubCfiTranslator.kt). The counts must
 * agree with the JVM version's exactly: a cross-EPUB index built on one platform and read on the
 * other would otherwise map positions to the wrong place.
 */
object IosEpubTextChars {

    /** Count readable characters in a parsed body (or any element subtree). */
    fun countReadableChars(node: Node): Long = when (node) {
        is TextNode -> if (node.getWholeText().isNotBlank()) node.getWholeText().length.toLong() else 0L
        is Element -> node.childNodes().sumOf { countReadableChars(it) }
        else -> 0L
    }

    /** Parse [html] and count the readable characters of its `<body>`. */
    fun countReadableChars(html: String): Long =
        countReadableChars(Ksoup.parse(html).body())

    /**
     * The within-chapter progression (0..1) of the start of the element with [elementId]
     * in [html]: readable characters before that element divided by the chapter total.
     * Returns `null` when the id is absent or the chapter has no readable characters.
     */
    fun progressionOfElementId(html: String, elementId: String): Double? {
        val doc = Ksoup.parse(html)
        val body = doc.body()
        val target = doc.getElementById(elementId) ?: return null
        val total = countReadableChars(body)
        if (total == 0L) return null
        val before = countReadableCharsBefore(body, target)
        if (before < 0L) return null
        return (before.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * Within-chapter progressions (0..1) for many element ids in a single parse and document-order
     * walk — the start offset of each id's element over the chapter's readable-character total.
     * Equivalent to calling [progressionOfElementId] per id but O(chapter) instead of O(ids ×
     * chapter): a readaloud chapter has thousands of SMIL fragments, so per-id re-parsing is
     * pathological. Ids absent from the document (or a chapter with no readable text) are omitted.
     */
    fun progressionsOfElementIds(html: String, elementIds: Set<String>): Map<String, Double> {
        if (elementIds.isEmpty()) return emptyMap()
        val body = Ksoup.parse(html).body()
        val total = countReadableChars(body)
        if (total == 0L) return emptyMap()
        val result = HashMap<String, Double>(elementIds.size)
        var count = 0L
        fun visit(node: Node) {
            when (node) {
                is TextNode -> if (node.getWholeText().isNotBlank()) count += node.getWholeText().length
                is Element -> {
                    val id = node.id()
                    // Record at element entry, before its own text — readable chars *before* it.
                    if (id.isNotEmpty() && id in elementIds && id !in result) {
                        result[id] = (count.toDouble() / total).coerceIn(0.0, 1.0)
                    }
                    node.childNodes().forEach { visit(it) }
                }
            }
        }
        body.childNodes().forEach { visit(it) }
        return result
    }

    /** Readable characters before [target] in document order, or -1 if not found. */
    private fun countReadableCharsBefore(body: Element, target: Element): Long {
        var count = 0L
        var found = false
        fun visit(node: Node) {
            if (found) return
            when {
                node === target -> found = true
                node is TextNode -> if (node.getWholeText().isNotBlank()) count += node.getWholeText().length
                node is Element -> node.childNodes().forEach { visit(it) }
            }
        }
        body.childNodes().forEach { visit(it) }
        return if (found) count else -1L
    }
}

package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * [EpubChapterDomOps] backed by ksoup, the Kotlin Multiplatform port of jsoup.
 *
 * Mirrors [JvmEpubChapterDomOps] step for step — the two must produce identical output for the
 * same chapter, which the shared highlight tests in `feature:reader`'s `commonTest` enforce by
 * running on both platforms.
 */
object IosEpubChapterDomOps : EpubChapterDomOps {

    override fun readableBodyText(html: String): String {
        val out = StringBuilder()
        fun visit(node: Node) {
            when (node) {
                is TextNode -> if (!node.getWholeText().isBlank()) out.append(node.getWholeText())
                is Element -> node.childNodes().forEach(::visit)
            }
        }
        Ksoup.parse(html).body().childNodes().forEach(::visit)
        return out.toString()
    }

    override fun figures(html: String): List<RawChapterFigure> {
        val body = Ksoup.parse(html).body()
        val out = mutableListOf<RawChapterFigure>()
        val ids = mutableListOf<Element>()
        var running = 0L

        // Identity-keyed lookup: ksoup's Element equality is structural, so two distinct but
        // identical <img> tags would collide in a hash map. Index by reference instead.
        fun idOf(el: Element): Int {
            val existing = ids.indexOfFirst { it === el }
            if (existing >= 0) return existing
            ids.add(el)
            return ids.size - 1
        }

        fun visit(node: Node) {
            when (node) {
                is TextNode -> if (node.getWholeText().isNotBlank()) running += node.getWholeText().length
                is Element -> {
                    val tag = node.tagName().lowercase()
                    val isFigureTag = tag in FIGURE_TAGS
                    val elemStart = running
                    node.childNodes().forEach(::visit)
                    val elemEnd = running
                    if (!isFigureTag) return
                    val target = when (tag) {
                        "figure" -> node.selectFirst("img") ?: node.selectFirst("svg") ?: node.selectFirst("picture")
                        else -> node
                    } ?: return
                    val targetTag = target.tagName().lowercase()
                    val figCap = target.parents()
                        .firstOrNull { it.tagName().equals("figure", ignoreCase = true) }
                        ?.selectFirst("figcaption")?.text()?.trim().orEmpty()
                    val caption = if (figCap.isNotEmpty()) {
                        figCap
                    } else {
                        target.attr("alt").ifBlank { target.attr("aria-label") }.trim()
                    }
                    val imageEl = if (targetTag == "picture") target.selectFirst("img") else target
                    out += RawChapterFigure(
                        tag = tag,
                        startChar = elemStart,
                        endChar = elemEnd,
                        targetId = idOf(target),
                        targetTag = targetTag,
                        caption = caption,
                        imageSrc = if (targetTag == "svg") null else imageEl?.attr("src")?.trim()?.ifBlank { null },
                        svgOuterHtml = if (targetTag == "svg") target.outerHtml() else null,
                    )
                }
            }
        }

        body.childNodes().forEach(::visit)
        return out
    }

    override fun buildCfiRange(spineStep: Int, html: String, startChar: Long, endChar: Long): String? {
        val doc = Ksoup.parse(html)
        val htmlEl = doc.child(0)
        val body = doc.body()
        val (startNode, startOffset) = iosFindNodeAtChar(body, startChar) ?: return null
        val (endNode, endOffset) = iosFindNodeAtChar(body, endChar) ?: return null
        val startPath = iosBuildCfiDocPath(htmlEl, startNode, startOffset) ?: return null
        val endPath = iosBuildCfiDocPath(htmlEl, endNode, endOffset) ?: return null
        return assembleCfiRange(spineStep, startPath, endPath)
    }
}

actual fun epubChapterDomOps(): EpubChapterDomOps = IosEpubChapterDomOps

actual fun epubCfiOps(): EpubCfiOps = IosEpubCfiOps

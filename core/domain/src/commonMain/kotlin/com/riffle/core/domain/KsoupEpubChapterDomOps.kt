package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * [EpubChapterDomOps] backed by ksoup, the Kotlin Multiplatform port of jsoup. Replaces the
 * parallel jvmMain (jsoup) and iosMain (ksoup) implementations — the DOM-walking algorithm is
 * identical on both platforms; only the parser import changed.
 *
 * Every implementation must walk identically: the same chapter HTML must yield the same readable
 * text, the same figure positions and the same range CFI on either platform, or a highlight
 * created on one device re-anchors to the wrong span on the other.
 *
 * Identity-keyed tracking: ksoup's Element equality is structural, so two distinct but identical
 * `<img>` tags collide in a HashMap. Index by reference via a list with `===` instead.
 */
object KsoupEpubChapterDomOps : EpubChapterDomOps {

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
        val (startNode, startOffset) = ksoupFindNodeAtChar(body, startChar) ?: return null
        val (endNode, endOffset) = ksoupFindNodeAtChar(body, endChar) ?: return null
        val startPath = ksoupBuildCfiDocPath(htmlEl, startNode, startOffset) ?: return null
        val endPath = ksoupBuildCfiDocPath(htmlEl, endNode, endOffset) ?: return null
        return assembleCfiRange(spineStep, startPath, endPath)
    }
}

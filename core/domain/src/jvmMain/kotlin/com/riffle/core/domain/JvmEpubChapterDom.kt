package com.riffle.core.domain

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** [EpubChapterDomOps] backed by jsoup. See [IosEpubChapterDomOps] for the Kotlin/Native twin. */
object JvmEpubChapterDomOps : EpubChapterDomOps {

    override fun readableBodyText(html: String): String {
        val out = StringBuilder()
        fun visit(node: Node) {
            when (node) {
                is TextNode -> if (!node.wholeText.isBlank()) out.append(node.wholeText)
                is Element -> node.childNodes().forEach(::visit)
            }
        }
        Jsoup.parse(html).body().childNodes().forEach(::visit)
        return out.toString()
    }

    override fun figures(html: String): List<RawChapterFigure> {
        val body = Jsoup.parse(html).body()
        val out = mutableListOf<RawChapterFigure>()
        val ids = HashMap<Element, Int>()
        var running = 0L

        fun idOf(el: Element): Int = ids.getOrPut(el) { ids.size }

        fun visit(node: Node) {
            when (node) {
                is TextNode -> if (node.wholeText.isNotBlank()) running += node.wholeText.length
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
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)
        val body = doc.body()
        val (startNode, startOffset) = findNodeAtChar(body, startChar) ?: return null
        val (endNode, endOffset) = findNodeAtChar(body, endChar) ?: return null
        val startPath = buildCfiDocPath(htmlEl, startNode, startOffset) ?: return null
        val endPath = buildCfiDocPath(htmlEl, endNode, endOffset) ?: return null
        return assembleCfiRange(spineStep, startPath, endPath)
    }
}

actual fun epubChapterDomOps(): EpubChapterDomOps = JvmEpubChapterDomOps

actual fun epubCfiOps(): EpubCfiOps = JvmEpubCfiOps

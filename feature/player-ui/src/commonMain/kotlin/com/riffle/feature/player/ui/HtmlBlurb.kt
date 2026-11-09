package com.riffle.feature.player.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Renders a book description served as an HTML fragment.
 *
 * Uses the Ksoup DOM (already in commonMain) to walk the tree and produce a styled
 * [AnnotatedString]. Both `<b>`/`<strong>` and `<i>`/`<em>` are preserved. `<br>`, `<p>`, and
 * `<div>` contribute newlines. HTML entities are decoded by Ksoup before we see the text.
 *
 * Replaces the former `expect`/`actual` split where Android used `AnnotatedString.fromHtml` and
 * iOS fell back to [stripHtmlToText].
 */
fun htmlBlurb(html: String): AnnotatedString = buildAnnotatedString {
    val body = Ksoup.parseBodyFragment(html).body()
    walkNode(body)
}

private fun AnnotatedString.Builder.walkNode(node: Node) {
    when (node) {
        is TextNode -> append(node.text())
        is Element -> {
            val tag = node.tagName().lowercase()
            when (tag) {
                "br" -> append('\n')
                "p", "div" -> {
                    walkChildren(node)
                    append('\n')
                }
                "b", "strong" -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    walkChildren(node)
                    pop()
                }
                "i", "em" -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    walkChildren(node)
                    pop()
                }
                else -> walkChildren(node)
            }
        }
        else -> {}
    }
}

private fun AnnotatedString.Builder.walkChildren(element: Element) {
    for (child in element.childNodes()) walkNode(child)
}

/**
 * Tags out, entities in: `"<p>Hello&nbsp;<b>world</b></p>"` → `"Hello world"`.
 *
 * Retained for callers that need a plain-text fallback, e.g. TTS synthesis which cannot
 * process AnnotatedString spans.
 */
fun stripHtmlToText(html: String): String = htmlBlurb(html).text

package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.FALLBACK_ORIGIN_FONT_FAMILY
import com.riffle.feature.reader.toCssRgba
import com.riffle.feature.reader.normalizeCaptionText
import com.riffle.feature.reader.splitSnippetForFiguresAt

// ─── Public entry point ───────────────────────────────────────────────────────

/**
 * Renders one [ChapterElision] into a self-contained XHTML document suitable for inclusion in
 * both the Android in-memory Readium [Publication] and the iOS synthetic on-disk EPUB.
 *
 * The produced HTML is byte-identical on both platforms — the same golden tests in commonTest
 * cover the shared code path. Platform-specific differences (Readium assets hostname for Android
 * vs an on-disk path for iOS) are not present in the XHTML itself.
 */
fun renderChapterHtml(
    chapter: ChapterElision,
    bookBodyFontFamily: String? = null,
    dataUriByHref: Map<String, String> = emptyMap(),
    publisherFontFaceCss: String = "",
    emphasisBarCss: String = EMPHASIS_ONLY_BAR_COLOR,
): String {
    val body = buildString {
        for (annotation in chapter.highlights) {
            when (annotation.type) {
                AnnotationEntity.TYPE_IMAGE -> appendImageAnnotation(this, annotation, dataUriByHref, emphasisBarCss)
                else -> appendInterleavedHighlight(this, annotation, bookBodyFontFamily, dataUriByHref, emphasisBarCss)
            }
        }
    }
    val title = chapter.title.xmlEscape()
    val realBodyFont = realCapturedFontOrNull(bookBodyFontFamily)
    val safeBodyFont = sanitizeCssFontFamily(realBodyFont)
    val bodyFontStyleBlock = if (safeBodyFont != null) {
        val escaped = safeBodyFont.xmlEscape()
        "body, h1, h2, h3, h4, h5, h6, aside, figcaption, .riffle-fig { font-family: $escaped; }"
    } else ""
    return """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title>$READIUM_DEFAULT_CSS_LINK<style>$publisherFontFaceCss
        |$ACCENT_BAR_TAP_CSS
        |$FIGURE_CENTERING_CSS
        |$NOTE_CALLOUT_CSS
        |$bodyFontStyleBlock</style></head>
        |<body>
        |  <h1>$title</h1>
        |${body.trimEnd('\n')}
        |</body></html>
    """.trimMargin()
}

// ─── Constants ────────────────────────────────────────────────────────────────

const val EMPHASIS_ONLY_BAR_COLOR = "rgba(128,128,128,1.0)"

const val ACCENT_BAR_TAP_CLASS = "riffle-hl-tap"

internal const val ACCENT_BAR_TAP_CSS =
    ".$ACCENT_BAR_TAP_CLASS{position:absolute;left:-4px;top:0;bottom:0;width:20px;" +
        "background:transparent;cursor:pointer;pointer-events:auto;}"

internal const val FIGURE_CENTERING_CSS =
    ".riffle-fig{margin:1em auto !important;text-align:center;position:relative;}" +
        ".riffle-fig>img,.riffle-fig>svg{display:block;margin:0 auto;max-width:100%;height:auto;}"

internal const val NOTE_CALLOUT_CSS =
    ".riffle-note{display:block;box-sizing:border-box;" +
        "margin:-0.55em 0 1.5em 16px !important;" +
        "padding:0.65em 0.8em 0.7em 12px !important;" +
        "border-radius:0 6px 6px 0;background:rgba(127,127,127,0.12) !important;" +
        "font-style:normal !important;opacity:1 !important;white-space:pre-wrap;}" +
        ".riffle-note::before{content:\"$ELIDED_NOTE_LABEL\";display:block;margin-bottom:0.3em;" +
        "font-size:0.72em;font-style:normal !important;font-weight:700;" +
        "letter-spacing:0.08em;text-transform:uppercase;opacity:0.65;}"

private const val READIUM_DEFAULT_CSS_LINK =
    "<link rel=\"stylesheet\" type=\"text/css\" " +
        "href=\"https://readium_assets/readium/readium-css/ReadiumCSS-default.css\"/>"

private const val PARAGRAPH_GAP_STYLE = "margin: 1em 0;"

private val ALLOWED_INLINE_SNIPPET_TAGS = setOf("em", "i", "strong", "b", "sup", "sub", "u", "s")

private val CAPTION_HIGHLIGHT_PREFIX_REGEX =
    Regex("^\\s*(Figure|Fig\\.?|Table|Chart)\\s+\\d", RegexOption.IGNORE_CASE)

// ─── Rendering helpers ────────────────────────────────────────────────────────

private fun appendInterleavedHighlight(
    sb: StringBuilder,
    highlight: AnnotationEntity,
    bookBodyFontFamily: String?,
    dataUriByHref: Map<String, String>,
    emphasisBarCss: String,
) {
    val figures = highlight.decodedEmbeddedFigures()?.sortedBy { it.order }.orEmpty()
    val normalizedSnippetOuter = normalizeCaptionText(highlight.textSnippet)
    if (figures.isEmpty() || figures.any { it.charOffset == null }) {
        val singleFigure = figures.singleOrNull()
        val isCaptionHighlight = singleFigure != null && (
            (singleFigure.caption.isNotBlank() &&
                normalizeCaptionText(singleFigure.caption) == normalizedSnippetOuter) ||
                (singleFigure.caption.isBlank() && CAPTION_HIGHLIGHT_PREFIX_REGEX.containsMatchIn(normalizedSnippetOuter))
            )
        if (isCaptionHighlight) {
            appendFigureBlock(sb, singleFigure.copy(caption = ""), highlight.id, highlight.color, dataUriByHref, emphasisBarCss)
            appendTextHighlight(sb, highlight, bookBodyFontFamily, emphasisBarCss)
            return
        }
        appendTextHighlight(sb, highlight, bookBodyFontFamily, emphasisBarCss)
        figures.forEach { fig ->
            val effective = if (
                fig.caption.isNotBlank() &&
                normalizeCaptionText(fig.caption) == normalizedSnippetOuter
            ) fig.copy(caption = "") else fig
            appendFigureBlock(sb, effective, highlight.id, highlight.color, dataUriByHref, emphasisBarCss)
        }
        return
    }
    val chunks = splitSnippetForFiguresAt(
        snippet = highlight.textSnippet,
        offsets = figures.map { it.charOffset },
    )
    val normalizedSnippet = normalizeCaptionText(highlight.textSnippet)
    chunks.forEachIndexed { index, chunk ->
        if (chunk.isNotEmpty()) {
            appendHighlightTextChunk(sb, highlight, chunk, bookBodyFontFamily, emphasisBarCss)
        }
        figures.getOrNull(index)?.let { fig ->
            val effectiveFigure = if (
                fig.caption.isNotBlank() &&
                normalizeCaptionText(fig.caption) == normalizedSnippet
            ) fig.copy(caption = "") else fig
            appendFigureBlock(sb, effectiveFigure, highlight.id, highlight.color, dataUriByHref, emphasisBarCss)
        }
    }
    appendNoteAside(sb, highlight, emphasisBarCss)
}

private fun appendHighlightTextChunk(
    sb: StringBuilder,
    highlight: AnnotationEntity,
    chunk: String,
    bookBodyFontFamily: String?,
    emphasisBarCss: String,
) {
    val hasColor = highlight.color.isNotBlank()
    val accent = if (hasColor) highlightBackgroundCss(highlight.color) else emphasisBarCss
    val barPx = if (hasColor) "4px" else "1.5px"
    val idEscaped = highlight.id.xmlEscape()
    val tapUrl = buildAnnotationTapUrl(highlight.id).xmlEscape()
    sb.append("  <p style=\"")
    sb.append(PARAGRAPH_GAP_STYLE)
    sb.append("; position: relative; border-left: $barPx solid ")
    sb.append(accent)
    sb.append(" !important; padding-left: 12px;")
    appendOriginFontFamilyStyle(sb, highlight.originFontFamily, bookBodyFontFamily)
    sb.append("\"><span class=\"")
    sb.append(ACCENT_BAR_TAP_CLASS)
    sb.append("\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\" onclick=\"var e=event,x=e.clientX,y=e.clientY;location.href='")
    sb.append(tapUrl)
    sb.append("?l='+x+'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1);return false;\"></span><span class=\"riffle-hl\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\"")
    appendEmphasisStyleAttr(sb, highlight.emphasisStyles)
    sb.append(">")
    sb.append(chunk.xmlEscape())
    sb.append("</span></p>\n")
}

private fun appendTextHighlight(
    sb: StringBuilder,
    highlight: AnnotationEntity,
    bookBodyFontFamily: String?,
    emphasisBarCss: String,
) {
    val hasColor = highlight.color.isNotBlank()
    val accent = if (hasColor) highlightBackgroundCss(highlight.color) else emphasisBarCss
    val barPx = if (hasColor) "4px" else "1.5px"
    val idEscaped = highlight.id.xmlEscape()
    val tapUrl = buildAnnotationTapUrl(highlight.id).xmlEscape()
    sb.append("  <p style=\"")
    sb.append(PARAGRAPH_GAP_STYLE)
    sb.append("; position: relative; border-left: $barPx solid ")
    sb.append(accent)
    sb.append(" !important; padding-left: 12px;")
    appendOriginFontFamilyStyle(sb, highlight.originFontFamily, bookBodyFontFamily)
    sb.append("\"><span class=\"")
    sb.append(ACCENT_BAR_TAP_CLASS)
    sb.append("\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\" onclick=\"var e=event,x=e.clientX,y=e.clientY;location.href='")
    sb.append(tapUrl)
    sb.append("?l='+x+'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1);return false;\"></span><span class=\"riffle-hl\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\"")
    appendEmphasisStyleAttr(sb, highlight.emphasisStyles)
    sb.append(">")
    val inlineHtml = highlight.textSnippetHtml
    if (inlineHtml != null) {
        sb.append(sanitizeInlineSnippetHtml(inlineHtml))
    } else {
        sb.append(highlight.textSnippet.xmlEscape())
    }
    sb.append("</span></p>\n")
    appendNoteAside(sb, highlight, emphasisBarCss)
}

private fun appendNoteAside(
    sb: StringBuilder,
    highlight: AnnotationEntity,
    emphasisBarCss: String,
) {
    val note = highlight.note ?: return
    val accent = if (highlight.color.isNotBlank()) {
        highlightBackgroundCss(highlight.color)
    } else {
        emphasisBarCss
    }
    sb.append("  <aside class=\"riffle-note\" data-ann-id=\"")
    sb.append(highlight.id.xmlEscape())
    sb.append("\" role=\"note\" aria-label=\"")
    sb.append(ELIDED_NOTE_ARIA_LABEL)
    sb.append("\" style=\"border-left: 2px solid ")
    sb.append(accent)
    sb.append(" !important;\">")
    sb.append(note.xmlEscape())
    sb.append("</aside>\n")
}

private fun appendOriginFontFamilyStyle(
    sb: StringBuilder,
    originFontFamily: String?,
    bookBodyFontFamily: String?,
) {
    val body = realCapturedFontOrNull(bookBodyFontFamily)
    val ownFont = realCapturedFontOrNull(originFontFamily)
    val raw = body ?: ownFont
    val safe = sanitizeCssFontFamily(raw) ?: return
    sb.append(" font-family: ")
    sb.append(safe.xmlEscape())
    sb.append(";")
}

private fun appendEmphasisStyleAttr(sb: StringBuilder, emphasisStyles: String?) {
    val styles = EmphasisStyle.decode(emphasisStyles) ?: return
    if (styles.isEmpty()) return
    val cssParts = buildList {
        if (EmphasisStyle.BOLD in styles) add("font-weight:bold")
        if (EmphasisStyle.ITALIC in styles) add("font-style:italic")
        val decorations = buildList {
            if (EmphasisStyle.UNDERLINE in styles) add("underline")
            if (EmphasisStyle.STRIKE in styles) add("line-through")
        }
        if (decorations.isNotEmpty()) add("text-decoration:${decorations.joinToString(" ")}")
    }
    if (cssParts.isEmpty()) return
    sb.append(" style=\"")
    sb.append(cssParts.joinToString(";"))
    sb.append("\"")
}

private fun appendImageAnnotation(
    sb: StringBuilder,
    annotation: AnnotationEntity,
    dataUriByHref: Map<String, String>,
    emphasisBarCss: String,
) {
    val effectiveBytes = annotation.imageBytes
        ?: annotation.imageHref?.let { dataUriByHref[it] }
    appendFigureFigure(
        sb = sb,
        annotationId = annotation.id,
        colorToken = annotation.color,
        svg = annotation.imageSvg,
        bytes = effectiveBytes,
        caption = annotation.textSnippet,
        emphasisBarCss = emphasisBarCss,
    )
}

private fun appendFigureBlock(
    sb: StringBuilder,
    figure: EmbeddedFigure,
    ownerAnnotationId: String,
    ownerColorToken: String,
    dataUriByHref: Map<String, String>,
    emphasisBarCss: String,
) {
    val effectiveBytes = figure.imageBytes ?: figure.href?.let { dataUriByHref[it] }
    appendFigureFigure(
        sb = sb,
        annotationId = ownerAnnotationId,
        colorToken = ownerColorToken,
        svg = figure.svg,
        bytes = effectiveBytes,
        caption = figure.caption,
        emphasisBarCss = emphasisBarCss,
    )
}

private fun appendFigureFigure(
    sb: StringBuilder,
    annotationId: String,
    colorToken: String,
    svg: String?,
    bytes: String?,
    caption: String,
    emphasisBarCss: String,
) {
    val hasColor = colorToken.isNotBlank()
    val accent = if (hasColor) highlightBackgroundCss(colorToken) else emphasisBarCss
    val barPx = if (hasColor) "4px" else "1.5px"
    val idEscaped = annotationId.xmlEscape()
    val tapUrl = buildAnnotationTapUrl(annotationId).xmlEscape()
    sb.append("  <figure class=\"riffle-fig\" data-ann-id=\"").append(idEscaped)
    sb.append("\" style=\"border-left: $barPx solid ")
    sb.append(accent)
    sb.append(" !important; padding-left: 12px;\">\n")
    sb.append("    <span class=\"").append(ACCENT_BAR_TAP_CLASS)
        .append("\" data-ann-id=\"").append(idEscaped)
        .append("\" onclick=\"var e=event,x=e.clientX,y=e.clientY;location.href='")
        .append(tapUrl)
        .append("?l='+x+'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1);return false;\"></span>\n")
    when {
        bytes != null -> sb.append("    <img src=\"").append(bytes.xmlAttrEscape())
            .append("\" data-ann-id=\"").append(idEscaped).append("\"/>")
        svg != null -> sb.append("    ").append(sanitizeSvgForElidedView(svg))
        else -> sb.append("    <p class=\"riffle-fig-placeholder\">[figure image not captured]</p>")
    }
    if (caption.isNotBlank()) {
        sb.append("\n    <figcaption>").append(caption.xmlEscape()).append("</figcaption>")
    }
    sb.append("\n  </figure>\n")
}

private fun highlightBackgroundCss(colorToken: String): String =
    HighlightColor.fromToken(colorToken).argb.toCssRgba()

// ─── String utilities ─────────────────────────────────────────────────────────

fun sanitizeCssFontFamily(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim()
    if (trimmed.any { c ->
            !(c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == ',' || c == '.' ||
                c == '\'' || c == '"')
        }) return null
    return trimmed
}

fun realCapturedFontOrNull(value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (value.trim() == FALLBACK_ORIGIN_FONT_FAMILY) return null
    val lower = value.trim().lowercase()
    val withoutQuotes = lower.removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
    if (withoutQuotes in GENERIC_CSS_FONT_KEYWORDS) return null
    return value.trim()
}

val GENERIC_CSS_FONT_KEYWORDS: Set<String> = setOf(
    "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui",
)

internal fun sanitizeSvgForElidedView(svg: String): String {
    val externalImageOrUse = Regex(
        """<(image|use)\b[^>]*?(?:xlink:)?href\s*=\s*["'](?!data:)[^"']*["'][^>]*/?>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    return externalImageOrUse.replace(svg, "")
}

internal fun sanitizeInlineSnippetHtml(html: String): String {
    val sb = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        val c = html[i]
        if (c != '<') {
            sb.append(c)
            i++
            continue
        }
        val end = html.indexOf('>', i)
        if (end < 0) break
        val body = html.substring(i + 1, end)
        val isClose = body.startsWith("/")
        val name = body.removePrefix("/")
            .substringBefore(' ')
            .substringBefore('/')
            .lowercase()
            .trim()
        if (name in ALLOWED_INLINE_SNIPPET_TAGS) {
            sb.append(if (isClose) "</" else "<").append(name).append('>')
        }
        i = end + 1
    }
    return sb.toString()
}

private fun String.xmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun String.xmlAttrEscape(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")

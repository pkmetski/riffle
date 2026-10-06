package com.riffle.feature.reader.highlights

/**
 * Shared PDF HTML assembly for the elided Annotations View (ADR 0048). Platform-specific rendering
 * (Android: WebView + PrintDocumentAdapter; iOS: WKWebView + UIGraphicsPDFRenderer) consumes the
 * output of [buildCombinedHtml] independently.
 */

private const val PDF_BASE_CSS =
    "body{font-size:14pt;line-height:1.6;margin:0;padding:16pt;}" +
        "h1{font-size:16pt;border-bottom:1px solid #cccccc;padding-bottom:4pt;" +
        "margin:24pt 0 12pt;page-break-after:avoid;}" +
        "h1:first-child{margin-top:0;}" +
        "p{margin:0.75em 0;}" +
        "aside{margin:0.5em 0;}"

/**
 * Assembles a single self-contained `<html>` document from [chapters] for PDF export. Each
 * chapter is rendered via [renderChapterHtml]; only the `<body>` content is extracted and
 * concatenated. The combined `<head>` substitutes [PDF_BASE_CSS] for the Readium CSS link, and
 * tap-dispatch spans are hidden.
 */
fun buildCombinedHtml(
    chapters: List<ChapterElision>,
    bookTitle: String?,
    figureBytesByHref: Map<String, String>,
    publisherFontFaceCss: String,
    bookBodyFontFamily: String?,
): String {
    val safeTitle = bookTitle
        ?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace("\"", "&quot;")
        ?: "Annotations"

    val bodyParts = chapters
        .filter { it.highlights.isNotEmpty() }
        .joinToString("\n") { chapter ->
            val chapterXhtml = renderChapterHtml(
                chapter, bookBodyFontFamily, figureBytesByHref, publisherFontFaceCss,
            )
            val bodyStart = chapterXhtml.indexOf("<body>").takeIf { it >= 0 } ?: return@joinToString ""
            val bodyEnd = chapterXhtml.lastIndexOf("</body>").takeIf { it >= 0 } ?: return@joinToString ""
            chapterXhtml.substring(bodyStart + "<body>".length, bodyEnd).trim()
        }

    val bodyFontRule = run {
        val safe = sanitizeCssFontFamily(realCapturedFontOrNull(bookBodyFontFamily)) ?: return@run ""
        "body,h1,h2,h3,h4,h5,h6{font-family:$safe;}"
    }

    return buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta charset=\"utf-8\"/>")
        append("<title>$safeTitle</title>")
        append("<style>")
        append(PDF_BASE_CSS)
        append(ACCENT_BAR_TAP_CSS)
        append(FIGURE_CENTERING_CSS)
        append(".$ACCENT_BAR_TAP_CLASS{display:none}")
        if (publisherFontFaceCss.isNotBlank()) append(publisherFontFaceCss)
        if (bodyFontRule.isNotBlank()) append(bodyFontRule)
        append("</style></head><body>")
        append(bodyParts)
        append("</body></html>")
    }
}

/**
 * Derives a filesystem-safe PDF filename from [bookTitle], falling back to [itemId]. Characters
 * illegal on FAT/NTFS/ext4 are replaced with underscores; base name capped at 180 chars.
 */
fun buildPdfFileName(bookTitle: String?, itemId: String): String {
    val illegal = Regex("[\\\\/:*?\"<>|]")
    val safeName = bookTitle
        ?.replace(illegal, "_")
        ?.trim()
        ?.take(180)
        ?.ifBlank { null }
        ?: itemId.replace(illegal, "_").trim().take(64)
    return "$safeName Annotations.pdf"
}

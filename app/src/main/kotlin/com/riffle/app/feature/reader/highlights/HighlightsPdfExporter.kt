package com.riffle.app.feature.reader.highlights

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exports the elided reader's full highlight set as a self-contained PDF via a headless
 * [android.webkit.WebView] and [android.print.PrintDocumentAdapter].
 *
 * [buildCombinedHtml] is an `internal` top-level function so JVM unit tests can exercise HTML
 * assembly without a live [Context] or [android.webkit.WebView].
 */
class HighlightsPdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: HighlightsPublicationFactory,
) {
    /**
     * Assembles the combined HTML for all chapters, renders it to a PDF via a headless WebView,
     * writes the result to `cacheDir/exports/annotations-<itemId>.pdf`, and returns a
     * [FileProvider] URI suitable for [android.content.Intent.ACTION_SEND].
     *
     * Must be called from a coroutine; the WebView and PrintDocumentAdapter callbacks run on the
     * main thread ([kotlinx.coroutines.Dispatchers.Main]).
     */
    suspend fun export(
        chapters: List<ChapterElision>,
        bookTitle: String?,
        itemId: String,
        figureBytesByHref: Map<String, String>,
        publisherFontFaceCss: String,
        bookBodyFontFamily: String?,
    ): Uri {
        val html = buildCombinedHtml(factory, chapters, bookTitle, figureBytesByHref, publisherFontFaceCss, bookBodyFontFamily)
        val exportsDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val pdfFile = File(exportsDir, "annotations-${itemId.take(64)}.pdf")
        renderToPdf(html, bookTitle, pdfFile)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    }

    // WebView + PrintDocumentAdapter rendering — implemented in Task 3.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private suspend fun renderToPdf(html: String, title: String?, outFile: File) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val webView = android.webkit.WebView(context)
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        try {
                        val adapter = webView.createPrintDocumentAdapter(title ?: "Annotations")
                        val attributes = android.print.PrintAttributes.Builder()
                            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(
                                android.print.PrintAttributes.Resolution("pdf", "pdf", 300, 300),
                            )
                            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
                            .build()
                        adapter.onLayout(
                            null, attributes, null,
                            object : android.print.PrintDocumentAdapter.LayoutResultCallback() {
                                override fun onLayoutFinished(
                                    info: android.print.PrintDocumentInfo,
                                    changed: Boolean,
                                ) {
                                    val pfd = android.os.ParcelFileDescriptor.open(
                                        outFile,
                                        android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                                            android.os.ParcelFileDescriptor.MODE_CREATE or
                                            android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                                    )
                                    adapter.onWrite(
                                        arrayOf(android.print.PageRange.ALL_PAGES),
                                        pfd,
                                        null,
                                        object : android.print.PrintDocumentAdapter.WriteResultCallback() {
                                            override fun onWriteFinished(
                                                pages: Array<out android.print.PageRange>,
                                            ) {
                                                pfd.close()
                                                adapter.onFinish()
                                                webView.destroy()
                                                cont.resume(Unit)
                                            }

                                            override fun onWriteFailed(error: CharSequence?) {
                                                pfd.close()
                                                adapter.onFinish()
                                                webView.destroy()
                                                cont.resumeWithException(
                                                    java.io.IOException("PDF write failed: $error"),
                                                )
                                            }

                                            override fun onWriteCancelled() {
                                                pfd.close()
                                                adapter.onFinish()
                                                webView.destroy()
                                                cont.resumeWithException(
                                                    java.io.IOException("PDF write cancelled"),
                                                )
                                            }
                                        },
                                    )
                                }

                                override fun onLayoutFailed(error: CharSequence?) {
                                    adapter.onFinish()
                                    webView.destroy()
                                    cont.resumeWithException(
                                        java.io.IOException("PDF layout failed: $error"),
                                    )
                                }

                                override fun onLayoutCancelled() {
                                    adapter.onFinish()
                                    webView.destroy()
                                    cont.resumeWithException(
                                        java.io.IOException("PDF layout cancelled"),
                                    )
                                }
                            },
                            null,
                        )
                        } catch (e: Exception) {
                            webView.destroy()
                            cont.resumeWithException(e)
                        }
                    }
                }
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                cont.invokeOnCancellation { webView.destroy() }
            }
        }
    }
}

// ─── PDF base styles ─────────────────────────────────────────────────────────

private const val PDF_BASE_CSS =
    "body{font-size:14pt;line-height:1.6;margin:0;padding:16pt;}" +
        "h1{font-size:16pt;border-bottom:1px solid #cccccc;padding-bottom:4pt;" +
        "margin:24pt 0 12pt;page-break-after:avoid;}" +
        "h1:first-child{margin-top:0;}" +
        "p{margin:0.75em 0;}" +
        "aside{margin:0.5em 0;}"

// ─── HTML assembly ────────────────────────────────────────────────────────────

/**
 * Assembles a single self-contained `<html>` document from [chapters] for PDF export.
 *
 * Each chapter is rendered via [HighlightsPublicationFactory.renderChapterHtml]; only the
 * `<body>` content is extracted and concatenated. The combined `<head>` substitutes
 * [PDF_BASE_CSS] for the `READIUM_DEFAULT_CSS_LINK` (which is served by Readium's
 * `WebViewServer` and is not resolvable outside the live reader WebView). Tap-dispatch spans
 * ([ACCENT_BAR_TAP_CLASS]) are hidden via CSS — they have no meaning in a static PDF.
 *
 * All images are already base64 data URIs inside the chapter HTML; the result is fully
 * self-contained with no external resource references.
 */
internal fun buildCombinedHtml(
    factory: HighlightsPublicationFactory,
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
            val chapterXhtml = factory.renderChapterHtml(
                chapter, bookBodyFontFamily, figureBytesByHref, publisherFontFaceCss,
            )
            // Extract content between <body> and </body>; the chapter XHTML is authored by
            // renderChapterHtml and always contains exactly one <body> block.
            val bodyStart = chapterXhtml.indexOf("<body>").takeIf { it >= 0 } ?: return@joinToString ""
            val bodyEnd = chapterXhtml.lastIndexOf("</body>").takeIf { it >= 0 } ?: return@joinToString ""
            chapterXhtml.substring(bodyStart + "<body>".length, bodyEnd).trim()
        }

    val bodyFontRule = run {
        val safe = sanitizeCssFontFamily(
            bookBodyFontFamily?.takeIf { it != FALLBACK_ORIGIN_FONT_FAMILY },
        ) ?: return@run ""
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
        // Hide tap-dispatch spans — they serve no purpose in a static PDF.
        append(".$ACCENT_BAR_TAP_CLASS{display:none}")
        if (publisherFontFaceCss.isNotBlank()) append(publisherFontFaceCss)
        if (bodyFontRule.isNotBlank()) append(bodyFontRule)
        append("</style></head><body>")
        append(bodyParts)
        append("</body></html>")
    }
}

package com.riffle.app.feature.reader.highlights

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.riffle.feature.reader.highlights.ChapterElision
import java.io.File
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
class HighlightsPdfExporter constructor(
    private val context: Context,
    private val factory: HighlightsPublicationFactory,
) {
    /**
     * Assembles the combined HTML for all chapters, renders it to a PDF via a headless WebView,
     * writes the result to `cacheDir/exports/<bookTitle> Annotations.pdf` (sanitized, falling back
     * to the item ID), and returns an [ExportResult] with the [FileProvider] URI and filename.
     *
     * Must be called from a coroutine; the WebView and PrintDocumentAdapter callbacks run on the
     * main thread ([kotlinx.coroutines.Dispatchers.Main]).
     */
    data class ExportResult(val uri: Uri, val fileName: String)

    suspend fun export(
        chapters: List<ChapterElision>,
        bookTitle: String?,
        itemId: String,
        figureBytesByHref: Map<String, String>,
        publisherFontFaceCss: String,
        bookBodyFontFamily: String?,
    ): ExportResult {
        val html = buildCombinedHtml(factory, chapters, bookTitle, figureBytesByHref, publisherFontFaceCss, bookBodyFontFamily)
        val exportsDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val fileName = buildPdfFileName(bookTitle, itemId)
        val pdfFile = File(exportsDir, fileName)
        renderToPdf(html, bookTitle, pdfFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        return ExportResult(uri, fileName)
    }

    // WebView + PrintDocumentAdapter rendering — implemented in Task 3.
    //
    // `PrintDocumentAdapter.LayoutResultCallback` and `WriteResultCallback` are hosted inside a
    // sealed-off Android SDK class hierarchy with package-private no-arg constructors. Subclassing
    // them from any package other than `android.print` requires both `INVISIBLE_MEMBER` (to reach
    // the constructor) and `INVISIBLE_REFERENCE` (to reach the callback types). The Kotlin
    // compiler emits an "unspecified behavior" note about `INVISIBLE_REFERENCE` — the only
    // non-suppressive alternative is to move this method into a Java helper in the correct
    // package, tracked as future work.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private suspend fun renderToPdf(html: String, title: String?, outFile: File) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val webView = android.webkit.WebView(context)
                // Tracks the open ParcelFileDescriptor so the cancellation handler can close it
                // if the coroutine is cancelled after onLayoutFinished opens it but before any
                // write callback closes it (otherwise the fd leaks).
                val openPfd = java.util.concurrent.atomic.AtomicReference<android.os.ParcelFileDescriptor?>(null)
                // Guard against WebView builds that fire onPageFinished more than once.
                var layoutStarted = false
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        if (layoutStarted) return
                        layoutStarted = true
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
                                    // onLayoutFinished is invoked asynchronously by the print
                                    // framework — it runs outside the try/catch above. Any throw
                                    // here must be caught and routed to the continuation.
                                    try {
                                        val pfd = android.os.ParcelFileDescriptor.open(
                                            outFile,
                                            android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                                                android.os.ParcelFileDescriptor.MODE_CREATE or
                                                android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                                        )
                                        openPfd.set(pfd)
                                        adapter.onWrite(
                                            arrayOf(android.print.PageRange.ALL_PAGES),
                                            pfd,
                                            null,
                                            object : android.print.PrintDocumentAdapter.WriteResultCallback() {
                                                override fun onWriteFinished(
                                                    pages: Array<out android.print.PageRange>,
                                                ) {
                                                    openPfd.getAndSet(null)?.close()
                                                    adapter.onFinish()
                                                    webView.destroy()
                                                    cont.resume(Unit)
                                                }

                                                override fun onWriteFailed(error: CharSequence?) {
                                                    openPfd.getAndSet(null)?.close()
                                                    adapter.onFinish()
                                                    webView.destroy()
                                                    cont.resumeWithException(
                                                        java.io.IOException("PDF write failed: $error"),
                                                    )
                                                }

                                                override fun onWriteCancelled() {
                                                    openPfd.getAndSet(null)?.close()
                                                    adapter.onFinish()
                                                    webView.destroy()
                                                    cont.resumeWithException(
                                                        java.io.IOException("PDF write cancelled"),
                                                    )
                                                }
                                            },
                                        )
                                    } catch (e: Exception) {
                                        openPfd.getAndSet(null)?.close()
                                        adapter.onFinish()
                                        webView.destroy()
                                        cont.resumeWithException(e)
                                    }
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
                cont.invokeOnCancellation {
                    openPfd.getAndSet(null)?.close()
                    webView.destroy()
                }
            }
        }
    }
}

// ─── Filename + HTML helpers — thin forwarders to feature:reader commonMain ───

/** Derives a filesystem-safe PDF filename. Delegates to the shared implementation. */
internal fun buildPdfFileName(bookTitle: String?, itemId: String): String =
    com.riffle.feature.reader.highlights.buildPdfFileName(bookTitle, itemId)

/**
 * Assembles a combined HTML document from [chapters] for PDF export. The [factory] parameter is
 * retained for API compatibility with existing tests but is not used — rendering delegates to the
 * shared `buildCombinedHtml` in `feature:reader` commonMain.
 */
internal fun buildCombinedHtml(
    @Suppress("UNUSED_PARAMETER") factory: HighlightsPublicationFactory,
    chapters: List<ChapterElision>,
    bookTitle: String?,
    figureBytesByHref: Map<String, String>,
    publisherFontFaceCss: String,
    bookBodyFontFamily: String?,
): String = com.riffle.feature.reader.highlights.buildCombinedHtml(
    chapters, bookTitle, figureBytesByHref, publisherFontFaceCss, bookBodyFontFamily,
)

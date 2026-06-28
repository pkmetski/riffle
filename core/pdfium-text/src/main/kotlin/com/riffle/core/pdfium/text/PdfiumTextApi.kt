package com.riffle.core.pdfium.text

import android.graphics.RectF

/**
 * Pdfium text-extraction APIs that the barteksc PdfiumAndroid binding never
 * exposed. Implemented as a parasitic JNI bridge that dlsym()s into the
 * `libmodpdfium.so` shipped in the APK by `com.github.barteksc:pdfium-android`
 * (pulled in transitively by Readium's adapter).
 *
 * **Lifecycle:** we explicitly call `System.loadLibrary("modpdfium")` from
 * our own classloader so we don't depend on any other module having touched
 * `com.shockwave.pdfium.PdfiumCore` first. Once the `.so` is loaded into
 * the process, our native bridge `dlopen("libmodpdfium.so", RTLD_NOLOAD)`s
 * a handle to it and resolves every `FPDFText_*` symbol once; subsequent
 * calls go through cached function pointers.
 *
 * **Independent document handles.** This API opens its own [FPDF_DOCUMENT]
 * (separate from whatever document handle barteksc holds). The two coexist —
 * Pdfium document handles are isolated. The additional memory cost is the
 * parse tree, a few hundred KB per PDF, which is negligible compared to
 * the rendered-page bitmap caches barteksc holds.
 */
object PdfiumTextApi {

    init {
        // Load the Pdfium native binary into the process. Different forks of
        // pdfium-android ship it under different names:
        //
        //   • marain87/PdfiumAndroid 1.9.x (Readium 3.3.0 transitive):
        //     libpdfium.cr.so (Pdfium renamed to "Chromium release"),
        //     libpdfiumandroid.so (its JNI bridge).
        //   • barteksc/pdfium-android 1.8.x (older Riffle, our smoke-test dep):
        //     libmodpdfium.so + libjniPdfium.so + libmodft2.so + libmodpng.so.
        //
        // We try both names so this module works in either consumer (Riffle
        // app via Readium → marain87, smoke-test via direct dep → barteksc).
        // System.loadLibrary is idempotent; calling it for an already-loaded
        // .so (e.g. PdfiumCore.<clinit> ran first) is a no-op.
        val loaded = runCatching { System.loadLibrary("pdfium.cr") }.isSuccess ||
            runCatching { System.loadLibrary("modpdfium") }.isSuccess
        if (!loaded) {
            throw UnsatisfiedLinkError(
                "Neither libpdfium.cr.so (marain87) nor libmodpdfium.so " +
                    "(barteksc) is loadable. Did the consumer drop the pdfium AAR?"
            )
        }
        System.loadLibrary("riffle_pdfium_text")
    }

    /**
     * Verifies that `libmodpdfium.so` is loaded and every required symbol
     * resolved. Returns `false` if PdfiumCore hasn't been touched yet or if
     * the binary doesn't export one of our expected symbols. Safe to call
     * repeatedly — resolution is memoized.
     */
    fun ensureResolved(): Boolean = nativeEnsureResolved()

    // --- Document / page lifecycle -----------------------------------------

    /** Opens a PDF document from a file path. Returns 0 on failure. */
    fun openDocument(path: String, password: String? = null): Long =
        nativeOpenDocument(path, password)

    /** Closes a document opened with [openDocument]. No-op on 0. */
    fun closeDocument(docPtr: Long) = nativeCloseDocument(docPtr)

    /** Total page count, or 0 if the document is invalid. */
    fun getPageCount(docPtr: Long): Int = nativeGetPageCount(docPtr)

    /** Opens a page (0-based). Returns 0 on failure. Caller must [closePage]. */
    fun openPage(docPtr: Long, pageIndex: Int): Long =
        nativeOpenPage(docPtr, pageIndex)

    /** Closes a page opened with [openPage]. No-op on 0. */
    fun closePage(pagePtr: Long) = nativeClosePage(pagePtr)

    /** Page width in PDF user-space units (points). */
    fun getPageWidth(pagePtr: Long): Double = nativeGetPageWidth(pagePtr)

    /** Page height in PDF user-space units (points). */
    fun getPageHeight(pagePtr: Long): Double = nativeGetPageHeight(pagePtr)

    // --- Text-page lifecycle -----------------------------------------------

    /**
     * Opens a text page for the given regular page. Returns 0 on failure.
     * The text page is a parsed text-layer representation Pdfium uses for
     * char queries; it's distinct from the page itself and must be closed
     * separately via [closeTextPage].
     */
    fun openTextPage(pagePtr: Long): Long = nativeOpenTextPage(pagePtr)

    /** Closes a text page opened with [openTextPage]. No-op on 0. */
    fun closeTextPage(textPagePtr: Long) = nativeCloseTextPage(textPagePtr)

    // --- Char & rect queries ------------------------------------------------

    /** Number of characters on the page (including whitespace). */
    fun countChars(textPagePtr: Long): Int = nativeCountChars(textPagePtr)

    /**
     * Bounding box of a single character in PDF user-space coordinates.
     * Returns null if the index is out of range or the char has no bounds
     * (some control characters).
     *
     * In PDF coordinates, Y grows upward. [RectF.top] holds the higher Y
     * (visually upper) and [RectF.bottom] holds the lower Y; this means
     * `top > bottom` numerically, which is the opposite of Android view
     * coordinates. Callers converting to view coordinates must flip Y.
     */
    fun getCharBox(textPagePtr: Long, charIndex: Int): RectF? =
        nativeGetCharBox(textPagePtr, charIndex)

    /**
     * Char index at a PDF-space point, or -1 if none within tolerance.
     * [tolX]/[tolY] are search radii (PDF units) around the point.
     */
    fun getCharIndexAtPos(
        textPagePtr: Long,
        x: Double,
        y: Double,
        tolX: Double = 1.0,
        tolY: Double = 1.0,
    ): Int = nativeGetCharIndexAtPos(textPagePtr, x, y, tolX, tolY)

    /**
     * Number of rectangles required to draw the selection covering
     * `[startIndex, startIndex + count)`. Multi-line selections return
     * multiple rects (one per visual line). 0 if the range is empty.
     */
    fun countRects(textPagePtr: Long, startIndex: Int, count: Int): Int =
        nativeCountRects(textPagePtr, startIndex, count)

    /**
     * One rectangle from the set produced by the most recent [countRects]
     * call on this text page. Y conventions match [getCharBox]. Returns
     * null if [rectIndex] is out of range.
     */
    fun getRect(textPagePtr: Long, rectIndex: Int): RectF? =
        nativeGetRect(textPagePtr, rectIndex)

    /** Returns all rectangles for the range as a List, in order. */
    fun rectsForRange(textPagePtr: Long, startIndex: Int, count: Int): List<RectF> {
        val n = countRects(textPagePtr, startIndex, count)
        if (n <= 0) return emptyList()
        return (0 until n).mapNotNull { getRect(textPagePtr, it) }
    }

    // --- Text extraction ----------------------------------------------------

    /**
     * Extracts text for `[startIndex, startIndex + count)`. Returns "" on
     * empty / invalid ranges.
     */
    fun getText(textPagePtr: Long, startIndex: Int, count: Int): String =
        nativeGetText(textPagePtr, startIndex, count)

    /**
     * Extracts text contained within the PDF-space rect. Coordinates follow
     * PDF convention (Y grows upward; top > bottom).
     */
    fun getBoundedText(textPagePtr: Long, bounds: RectF): String =
        nativeGetBoundedText(
            textPagePtr,
            bounds.left.toDouble(),
            bounds.top.toDouble(),
            bounds.right.toDouble(),
            bounds.bottom.toDouble(),
        )

    // --- Native declarations -----------------------------------------------

    private external fun nativeEnsureResolved(): Boolean
    private external fun nativeOpenDocument(path: String, password: String?): Long
    private external fun nativeCloseDocument(docPtr: Long)
    private external fun nativeGetPageCount(docPtr: Long): Int
    private external fun nativeOpenPage(docPtr: Long, pageIndex: Int): Long
    private external fun nativeClosePage(pagePtr: Long)
    private external fun nativeGetPageWidth(pagePtr: Long): Double
    private external fun nativeGetPageHeight(pagePtr: Long): Double
    private external fun nativeOpenTextPage(pagePtr: Long): Long
    private external fun nativeCloseTextPage(textPagePtr: Long)
    private external fun nativeCountChars(textPagePtr: Long): Int
    private external fun nativeGetCharBox(textPagePtr: Long, charIndex: Int): RectF?
    private external fun nativeGetCharIndexAtPos(
        textPagePtr: Long, x: Double, y: Double, tolX: Double, tolY: Double,
    ): Int
    private external fun nativeCountRects(textPagePtr: Long, startIndex: Int, count: Int): Int
    private external fun nativeGetRect(textPagePtr: Long, rectIndex: Int): RectF?
    private external fun nativeGetText(textPagePtr: Long, startIndex: Int, count: Int): String
    private external fun nativeGetBoundedText(
        textPagePtr: Long,
        left: Double, top: Double, right: Double, bottom: Double,
    ): String
}

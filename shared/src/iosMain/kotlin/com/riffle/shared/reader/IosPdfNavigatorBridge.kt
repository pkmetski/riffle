package com.riffle.shared.reader

import com.riffle.feature.reader.PdfOutlineNode
import platform.UIKit.UIViewController

/**
 * Obj-C-compatible seam between iosMain and the Swift-side PDFKit wrapper.
 *
 * Swift implementation: PdfKitNavigatorBridge (in iosApp/iosApp/).
 * Registered at startup via [IosPdfNavigatorBridgeFactory] passed to startKoin().
 */
interface IosPdfPageChangeCallback {
    fun onPageChanged(page: Int)
}

/** Callback fired when the user single-taps the PDF view (used to toggle reader chrome). */
interface IosPdfTapCallback {
    fun onTap()
}

interface IosPdfNavigatorBridge {
    /** UIViewController that hosts PDFKit's PDFView. Embed via UIKitViewController. */
    fun viewController(): UIViewController

    /**
     * Open the PDF at [filePath], optionally restoring [initialPage] (0-based).
     * Returns true if the document was opened successfully, false if it could not be opened.
     */
    fun openPdf(filePath: String, initialPage: Int): Boolean

    /** Returns the current 0-based page index, or 0 if the document is not open. */
    fun currentPage(): Int

    /** Total number of pages; 0 before [openPdf] completes. */
    fun pageCount(): Int

    /** Navigate to the given 0-based [pageIndex]. No-op if out of range. */
    fun goToPage(pageIndex: Int)

    /** Set the vertical gap in points between pages in continuous scroll mode. 0 = no gap. */
    fun setPageSpacing(spacingPts: Double)

    /**
     * Set the horizontal inset in points applied to both the leading and trailing edges of the
     * PDF view. Mirrors Android's horizontal padding that narrows the page width to enforce the
     * user's margin preference.
     */
    fun setHorizontalInsets(insetPts: Double)

    /** Register a callback invoked on every page change. Pass null to clear. */
    fun setPageChangeCallback(callback: IosPdfPageChangeCallback?)

    /** Register a callback invoked on a single tap on the PDF view. Pass null to clear. */
    fun setTapCallback(handler: IosPdfTapCallback?)

    /** Returns the PDF outline tree, or an empty list if the document has no outline. */
    fun outline(): List<PdfOutlineNode>

    /** Release PDFKit resources. Safe to call multiple times. */
    fun disposePdf()
}

/** Factory so Koin can produce one bridge instance per PDF reader open. */
interface IosPdfNavigatorBridgeFactory {
    fun create(): IosPdfNavigatorBridge
}

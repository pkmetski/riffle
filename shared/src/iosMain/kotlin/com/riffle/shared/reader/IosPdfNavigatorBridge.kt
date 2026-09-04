package com.riffle.shared.reader

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

interface IosPdfNavigatorBridge {
    /** UIViewController that hosts PDFKit's PDFView. Embed via UIKitViewController. */
    fun viewController(): UIViewController

    /** Open the PDF at [filePath], optionally restoring [initialPage] (0-based). */
    fun openPdf(filePath: String, initialPage: Int)

    /** Returns the current 0-based page index, or 0 if the document is not open. */
    fun currentPage(): Int

    /** Total number of pages; 0 before [openPdf] completes. */
    fun pageCount(): Int

    /** Navigate to the given 0-based [pageIndex]. No-op if out of range. */
    fun goToPage(pageIndex: Int)

    /** Register a callback invoked on every page change. Pass null to clear. */
    fun setPageChangeCallback(callback: IosPdfPageChangeCallback?)

    /** Release PDFKit resources. Safe to call multiple times. */
    fun disposePdf()
}

/** Factory so Koin can produce one bridge instance per PDF reader open. */
interface IosPdfNavigatorBridgeFactory {
    fun create(): IosPdfNavigatorBridge
}

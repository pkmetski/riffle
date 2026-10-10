package com.riffle.shared.reader

/**
 * Obj-C-compatible seam for exporting the elided Annotations View as a PDF and presenting the
 * system share sheet. Implemented in Swift via WKWebView.createPDF + UIActivityViewController.
 *
 * [exportAndShare] must be called from the main thread. It is fire-and-forget: the Swift side
 * renders the PDF asynchronously, writes it to a temp file, then presents the share sheet from
 * the key window's root view controller. Errors are silently ignored (the share sheet simply
 * doesn't appear).
 */
interface IosElidedPdfBridge {
    fun exportAndShare(html: String, fileName: String)
}

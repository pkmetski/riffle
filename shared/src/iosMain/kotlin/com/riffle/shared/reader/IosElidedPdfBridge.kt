package com.riffle.shared.reader

/**
 * Obj-C-compatible seam for exporting the elided Annotations View as a PDF and presenting the
 * system share sheet. Implemented in Swift via WKWebView.createPDF + UIActivityViewController.
 *
 * [exportAndShare] must be called from the main thread. [onComplete] is invoked on the main
 * thread after the share sheet has been presented (or if rendering failed). The caller uses this
 * to reset the loading spinner.
 */
interface IosElidedPdfBridge {
    fun exportAndShare(html: String, fileName: String, onComplete: (() -> Unit)?)
}

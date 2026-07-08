package com.riffle.core.catalog

/**
 * The book "kind" a Catalog item advertises. Wider than `core:domain`'s `EbookFormat` — includes
 * `Audiobook` for audio-only items. Callers pick a specific format when calling `fetchFile`.
 */
sealed class BookFormat {
    data object Epub : BookFormat()
    data object Pdf : BookFormat()
    data object Audiobook : BookFormat()
    data object Unsupported : BookFormat()
}

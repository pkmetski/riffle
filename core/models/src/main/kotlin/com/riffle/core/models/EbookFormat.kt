package com.riffle.core.models

sealed class EbookFormat {
    data object Epub : EbookFormat()
    data object Pdf : EbookFormat()
    data object Cbz : EbookFormat()
    data object Unsupported : EbookFormat()

    fun toStorageString(): String = when (this) {
        Epub -> STORAGE_EPUB
        Pdf -> STORAGE_PDF
        Cbz -> STORAGE_CBZ
        Unsupported -> STORAGE_UNSUPPORTED
    }

    companion object {
        const val STORAGE_EPUB = "epub"
        const val STORAGE_PDF = "pdf"
        const val STORAGE_CBZ = "cbz"
        const val STORAGE_UNSUPPORTED = "unsupported"

        fun from(raw: String?): EbookFormat = when (raw?.lowercase()) {
            STORAGE_EPUB -> Epub
            STORAGE_PDF -> Pdf
            STORAGE_CBZ -> Cbz
            else -> Unsupported
        }
    }
}

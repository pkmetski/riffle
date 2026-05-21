package com.riffle.core.domain

sealed class EbookFormat {
    data object Epub : EbookFormat()
    data object Pdf : EbookFormat()
    data object Unsupported : EbookFormat()

    fun toStorageString(): String = when (this) {
        Epub -> "epub"
        Pdf -> "pdf"
        Unsupported -> "unsupported"
    }

    companion object {
        fun from(raw: String?): EbookFormat = when (raw?.lowercase()) {
            "epub" -> Epub
            "pdf" -> Pdf
            else -> Unsupported
        }
    }
}

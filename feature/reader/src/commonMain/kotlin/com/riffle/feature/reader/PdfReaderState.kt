package com.riffle.feature.reader

sealed interface PdfReaderState {
    data object Loading : PdfReaderState
    /** Metadata loaded; waiting for the platform navigator to open and confirm page count + outline. */
    data class AwaitingNavigator(val title: String, val initialPage: Int) : PdfReaderState
    data class Ready(val title: String, val totalPages: Int) : PdfReaderState
    data class Error(val message: String) : PdfReaderState
    data object BookNotFound : PdfReaderState
}

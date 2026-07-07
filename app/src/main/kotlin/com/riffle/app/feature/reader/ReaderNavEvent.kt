package com.riffle.app.feature.reader

/**
 * Navigation events emitted by [EpubReaderViewModel] that the nav host (MainScreen) must act on
 * outside the reader's own back stack — e.g. leaving the elided Highlights-mode reader to open the
 * real source book (ADR 0041, Task 9).
 */
sealed interface ReaderNavEvent {
    data class OpenInSourceBook(val sourceId: String, val itemId: String, val cfi: String) : ReaderNavEvent
}

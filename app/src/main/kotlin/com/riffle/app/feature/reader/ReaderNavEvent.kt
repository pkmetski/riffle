package com.riffle.app.feature.reader

/**
 * Navigation events emitted by [EpubReaderViewModel] that the nav host (MainScreen) must act on
 * outside the reader's own back stack — e.g. leaving the elided Highlights-mode reader to open the
 * real source book (ADR 0041, Task 9).
 */
sealed interface ReaderNavEvent {
    data class OpenInSourceBook(val sourceId: String, val itemId: String, val cfi: String) : ReaderNavEvent

    /**
     * Highlights-mode reader has no highlights left (user deleted the last one). The synthesised
     * Publication would have an empty readingOrder — Readium's navigator crashes on that — so the
     * nav host must pop the reader off the back stack instead of letting the VM reopen an empty
     * book.
     */
    object CloseEmptyHighlights : ReaderNavEvent
}

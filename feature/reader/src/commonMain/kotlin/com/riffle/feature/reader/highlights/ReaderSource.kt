package com.riffle.feature.reader.highlights

/** Which publication the EPUB reader is rendering. */
enum class ReaderSource {
    /** The full downloaded book file (normal reading). */
    FullBook,
    /** Elided reader (ADR 0048): a synthesised publication rendered from the local annotation
     *  store so only annotated passages are shown. */
    Highlights,
}

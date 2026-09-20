package com.riffle.core.models

data class LibraryItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val readingProgress: Float,
    val isCached: Boolean,
    val isDownloaded: Boolean,
    val ebookFormat: EbookFormat,
    val ebookFileIno: String? = null,
    // True when the ABS item carries audio (audiobook or combined item). Drives which matched
    // ABS item receives audiobook `currentTime` progress for a readaloud (ADR 0023).
    val hasAudio: Boolean = false,
    // Total audio length in seconds (0 when no audio). Sent with audiobook progress so ABS reports
    // a real percentage rather than 0% on the first sync.
    val audioDurationSec: Double = 0.0,
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val lastOpenedAt: Long? = null,
    val addedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
    // The owning Server. Item ids are only unique within a Server (ADR 0031), so callers that key
    // local files / DB rows must pair id with sourceId. Defaulted for construction sites (e.g.
    // tests) that don't care; the real value is set when mapping from the DB entity.
    val sourceId: String = "",
    // Total pages for formats where that's a discrete concept — comics today (from the CBZ image
    // count computed at Add-to-Library); potentially PDF/EPUB in future. Null for EPUB (reflowable,
    // no natural page count) and audiobook-only items.
    val pageCount: Int? = null,
) {
    /** Has an ebook file Riffle can open in the reader (EPUB, PDF, or CBZ). */
    val isReadable: Boolean get() = ebookFormat != EbookFormat.Unsupported

    /**
     * True when the item's format can carry text highlights + notes. EPUB and PDF do; CBZ (a
     * comic archive of raster pages) does not. Library surfaces gate the Annotations tab and per-
     * item highlight actions on this, not on [isReadable] alone — a Comics library is entirely
     * CBZ and would otherwise present a dead Annotations tab that never fills.
     */
    val canAnnotate: Boolean get() =
        ebookFormat == EbookFormat.Epub || ebookFormat == EbookFormat.Pdf

    /** Has audio Riffle can play in the audiobook player — an Audiobook (ADR 0035). */
    val isListenable: Boolean get() = hasAudio

    /**
     * The item has at least one thing Riffle can open — readable or listenable. Replaces the old
     * `isSupported`, which conflated "has an ebook" with "is openable at all"; audiobook-only items
     * are now openable (they Listen), so library surfaces gate on this, while ebook-target selection
     * gates specifically on [isReadable].
     */
    val isPlayable: Boolean get() = isReadable || isListenable

    /**
     * The item is audio and nothing else, so cover surfaces draw the waveform glyph rather than
     * the shelf glyph. One concept that used to have three rules: Android's three call sites
     * asked `isListenable && !isReadable`, iOS's detail screen asked `hasAudio` (so a Readaloud
     * book — an EPUB *with* audio — drew a waveform where its cover art belongs), and iOS's
     * library grid asked `coversAreSquare`, a per-library layout flag that has nothing to do
     * with the individual item. Android's rule won; every surface asks this.
     */
    val isAudiobookOnly: Boolean get() = isListenable && !isReadable
}

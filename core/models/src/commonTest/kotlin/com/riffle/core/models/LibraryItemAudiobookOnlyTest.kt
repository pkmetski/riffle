package com.riffle.core.models

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [LibraryItem.isAudiobookOnly], the one rule that decides whether a cover surface draws the
 * waveform glyph or the shelf glyph.
 *
 * The concept used to have three rules. Android asked `isListenable && !isReadable` at three call
 * sites; iOS's detail screen asked `hasAudio`, so a Readaloud book — an EPUB that happens to carry
 * narration — drew a waveform where its cover art belongs; and iOS's library grid asked
 * `coversAreSquare`, a per-library layout flag that says nothing about the individual item.
 * [readaloudBookIsNotAudiobookOnly] is the case that separates this rule from `hasAudio` and is
 * what flips red if a caller reverts to it.
 */
class LibraryItemAudiobookOnlyTest {

    private fun item(format: EbookFormat, hasAudio: Boolean) = LibraryItem(
        id = "i",
        libraryId = "l",
        title = "t",
        author = "a",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = format,
        hasAudio = hasAudio,
    )

    @Test fun audioWithNoReadableEbookIsAudiobookOnly() {
        assertTrue(item(EbookFormat.Unsupported, hasAudio = true).isAudiobookOnly)
    }

    @Test fun readaloudBookIsNotAudiobookOnly() {
        // An EPUB with narration. `hasAudio` is true, so the old iOS rule drew a waveform.
        assertFalse(item(EbookFormat.Epub, hasAudio = true).isAudiobookOnly)
    }

    @Test fun plainEbookIsNotAudiobookOnly() {
        assertFalse(item(EbookFormat.Epub, hasAudio = false).isAudiobookOnly)
    }

    @Test fun comicIsNotAudiobookOnly() {
        assertFalse(item(EbookFormat.Cbz, hasAudio = false).isAudiobookOnly)
    }

    @Test fun itemWithNeitherEbookNorAudioIsNotAudiobookOnly() {
        assertFalse(item(EbookFormat.Unsupported, hasAudio = false).isAudiobookOnly)
    }
}

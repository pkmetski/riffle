package com.riffle.core.domain

/** One spine chapter of an EPUB: its manifest href and its (already-extracted) HTML. */
data class EpubChapterHtml(
    val href: String,
    val html: String,
)

/**
 * The spine chapters and media-overlay clips extracted from one EPUB.
 *
 * Shared by jvmMain's [EpubContentExtractor] and iosMain's [IosEpubContentExtractor] so the
 * cross-EPUB index (ADR 0023) is built from the identical shape on both platforms.
 */
data class ExtractedEpub(
    val chapters: List<EpubChapterHtml>,
    val smilClips: List<MediaOverlayClip>,
)

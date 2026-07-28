package com.riffle.core.domain

import kotlinx.serialization.Serializable

/**
 * Readable-character counts for one logical chapter as it appears in each of the two
 * EPUBs of a matched readaloud book. A position inside the chapter is bridged between
 * the two CFI domains by scaling its character offset proportionally between
 * [absChars] and [storytellerChars] — best-effort, per ADR 0019.
 */
@Serializable
data class ChapterCharMap(
    val absChars: Long,
    val storytellerChars: Long,
)

/**
 * The per-matched-book cross-EPUB character-position index (ADR 0019): one
 * [ChapterCharMap] per chapter, aligned by spine order. Serialisable for Room
 * persistence keyed by the two EPUBs' checksums.
 */
@Serializable
data class CrossEpubIndex(
    val perChapter: List<ChapterCharMap>,
)

/**
 * A position within a logical book that is EPUB-file-agnostic: a spine-aligned chapter
 * index plus a [progression] (0..1) through that chapter's readable characters. This is
 * the canonical currency [DefaultPositionTranslator] converts between coordinate
 * systems; the reader translates it to/from the displayed EPUB's CFI at its boundary.
 */
data class ChapterProgression(
    val chapterIndex: Int,
    val progression: Double,
)

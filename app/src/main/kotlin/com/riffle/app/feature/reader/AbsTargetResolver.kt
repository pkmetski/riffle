package com.riffle.app.feature.reader

import com.riffle.core.models.ReadaloudLink

/** A readaloud's ABS link paired with the media its Library Item carries. */
data class AbsLinkMedia(
    val link: ReadaloudLink,
    val isReadable: Boolean,
    val hasAudio: Boolean,
    val audioDurationSec: Double = 0.0,
)

/** The matched ABS items a readaloud cycle pushes each progress kind to (either may be absent). */
data class ResolvedAbsTargets(val ebook: ReadaloudLink?, val audio: ReadaloudLink?)

/**
 * Routes a readaloud's progress to the matched ABS items by media type (ADR 0019). The ebook
 * target is a matched item that carries an ebook — the opened item when it qualifies, since it is
 * the displayed EPUB and canonical frame (ADR 0026). The audio target is a matched item that
 * carries audio. One combined item satisfies both; split libraries yield two distinct items.
 */
fun resolveAbsTargets(openedItemId: String, items: List<AbsLinkMedia>): ResolvedAbsTargets {
    val ebook = items.firstOrNull { it.link.absLibraryItemId == openedItemId && it.isReadable }?.link
        ?: items.firstOrNull { it.isReadable }?.link
    val audio = items.firstOrNull { it.hasAudio }?.link
    return ResolvedAbsTargets(ebook, audio)
}

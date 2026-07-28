package com.riffle.core.domain.sentence

import com.riffle.core.domain.SentenceQuote

/**
 * Fragment reference — typically `"href#spanId"`, or rarely `"href"` for non-anchored sentences.
 */
typealias FragmentRef = String

/**
 * Provides sentence quotes and chapter hrefs for a currently-open book,
 * shared between Readaloud and Cadence (silent highlight driven by WPM tick).
 *
 * Implementations drive the position pipeline: either from Storyteller's
 * SMIL data (Readaloud) or from a WPM simulation (Cadence).
 */
interface SentenceSource {
    /**
     * Load sentence quotes for every chapter in the publication opened for [openBookId].
     * Implementations that inject DOM spans (e.g. DomSentenceSource) do so as part of loading;
     * callers only see the resulting fragment→quote map and trust that the fragment refs
     * resolve inside the WebView.
     */
    suspend fun loadAll(): Map<FragmentRef, SentenceQuote>

    /**
     * Chapter href for each fragment ref — used to scope "play/highlight from here"
     * lookups to the correct chapter and drive chapter navigation.
     */
    suspend fun chapterHrefs(): Map<FragmentRef, String>
}

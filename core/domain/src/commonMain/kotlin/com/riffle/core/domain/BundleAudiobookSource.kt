package com.riffle.core.domain

/**
 * Produces an [AudiobookSession] for an ABS item from a downloaded bundle's audio, when one exists.
 * This is the third [AudiobookSession] producer (alongside [AudiobookRepository] for streaming and
 * [AudiobookDownloadRepository] for the dedicated download); the player prefers a dedicated download,
 * then a bundle, then streaming.
 *
 * All knowledge of which bundle type backs the audio (today: a Storyteller readaloud bundle) lives in
 * the single implementation. Removing or replacing that bundle type is a one-class change behind this
 * interface; the audiobook stack and the library are unaffected.
 */
interface BundleAudiobookSource {
    /** A playable session backed by a downloaded bundle's audio for this ABS item, or null if none. */
    suspend fun localSession(sourceId: String, itemId: String): AudiobookSession?

    /**
     * True when a downloaded bundle can satisfy this ABS item's audio offline. Synchronous so it backs
     * the library's offline filter without making that predicate suspend.
     */
    fun isAvailableOffline(sourceId: String, itemId: String): Boolean
}

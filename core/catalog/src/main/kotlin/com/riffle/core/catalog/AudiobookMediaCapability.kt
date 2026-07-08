package com.riffle.core.catalog

interface AudiobookMediaCapability : CatalogCapability {
    suspend fun getTracks(itemId: String): List<CatalogAudioTrack>
    /** Returns `null` when the item carries no audiobook on this Source (distinct from a fetch
     *  failure, which throws). Callers persist the NO_AUDIOBOOK verdict definitively on null. */
    suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint?

    /** Direct streaming URL for a specific track. Includes any auth token needed for playback. */
    fun buildStreamUrl(itemId: String, trackIno: String): String

    /**
     * Open a playable audiobook session for [itemId]. Returns tracks (with playable URLs baked in),
     * chapters, and the server's last-known position + lastUpdate stamp — one composite call so a
     * repository can spin up a media3 stream + last-update-wins reconciler in one round-trip.
     * Returns `null` when the item has no audiobook or the session cannot be opened.
     */
    suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream?

    /**
     * Chapter markers for [itemId]'s audiobook, without opening a playback session. Populates the
     * local chapter cache eagerly on the library detail screen. Returns empty when the item has no
     * audiobook chapters (rare — most audiobooks carry at least one).
     */
    suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter>
}

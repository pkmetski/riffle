package com.riffle.core.catalog

interface AudiobookMediaCapability : CatalogCapability {
    suspend fun getTracks(itemId: String): List<CatalogAudioTrack>
    suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint

    /** Direct streaming URL for a specific track. Independent of any open reading session. */
    fun buildStreamUrl(itemId: String, trackIno: String): String
}

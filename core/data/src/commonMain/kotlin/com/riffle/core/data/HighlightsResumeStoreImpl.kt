package com.riffle.core.data

import com.riffle.core.domain.HighlightsResumeStore

class HighlightsResumeStoreImpl(private val store: KeyValueStore) : HighlightsResumeStore {

    override suspend fun lastHighlightId(serverId: String, itemId: String): String? =
        store.getString(keyFor(serverId, itemId))

    override suspend fun setLastHighlightId(serverId: String, itemId: String, annotationId: String) =
        store.setString(keyFor(serverId, itemId), annotationId)

    private fun keyFor(serverId: String, itemId: String) = "highlights_resume_${serverId}_$itemId"
}

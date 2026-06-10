package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import javax.inject.Inject

/**
 * Resolves the canonical audio-settings key (ADR 0028): if any ABS item linked to the readaloud
 * carries audio (`hasAudio`), that audiobook's id owns the settings; otherwise the Storyteller
 * readaloud id does. The cardinality is 0–1 audiobook per readaloud; the sort keeps the key stable
 * if the data is ever dirty.
 */
class AudioIdentityResolverImpl @Inject constructor(
    private val linkDao: ReadaloudLinkDao,
    private val libraryItemDao: LibraryItemDao,
) : AudioIdentityResolver {

    override suspend fun resolveForStorytellerBook(
        storytellerServerId: String,
        storytellerBookId: String,
    ): AudioIdentity {
        val audiobook = linkDao.findByStorytellerBook(storytellerServerId, storytellerBookId)
            .sortedBy { it.absLibraryItemId }
            .firstOrNull { libraryItemDao.getById(it.absServerId, it.absLibraryItemId)?.hasAudio == true }
        return if (audiobook != null) {
            AudioIdentity(audiobook.absServerId, audiobook.absLibraryItemId)
        } else {
            AudioIdentity(storytellerServerId, storytellerBookId)
        }
    }
}

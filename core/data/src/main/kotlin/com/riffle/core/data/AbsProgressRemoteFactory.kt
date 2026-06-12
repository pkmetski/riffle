package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.Server
import com.riffle.core.network.AbsSessionApi
import javax.inject.Inject

/**
 * Builds the ABS [ProgressRemote]s, sourcing the auxiliary metadata each PATCH needs from the
 * locally-persisted `library_items` row (the ebook progress fraction / the audio duration), so the
 * sweep can push without re-reading it from the network (ADR 0030).
 */
class AbsProgressRemoteFactory @Inject constructor(
    private val api: AbsSessionApi,
    private val libraryItemDao: LibraryItemDao,
) : ProgressRemoteFactory {

    override fun ebook(server: Server, token: String, itemId: String): ProgressRemote<String> =
        AbsEbookProgressRemote(api, server.url.value, token, server.insecureConnectionAllowed, itemId) {
            libraryItemDao.getById(server.id, itemId)?.readingProgress ?: 0f
        }

    override fun audio(server: Server, token: String, itemId: String): ProgressRemote<Double> =
        AbsAudioProgressRemote(api, server.url.value, token, server.insecureConnectionAllowed, itemId) {
            libraryItemDao.getById(server.id, itemId)?.audioDurationSec ?: 0.0
        }
}

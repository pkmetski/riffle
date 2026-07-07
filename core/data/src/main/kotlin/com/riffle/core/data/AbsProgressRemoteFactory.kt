package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.Source
import com.riffle.core.network.AbsSessionApi
import javax.inject.Inject

/**
 * Builds the ABS [ProgressRemote]s, sourcing the auxiliary metadata each PATCH needs from the
 * locally-persisted `library_items` row (the ebook progress fraction / the audio duration), so the
 * sweep can push without re-reading it from the network (ADR 0030).
 *
 * For ebook items, [translatorFactory] produces a per-item CFI↔Locator converter (ADR 0013): the
 * converter translates ABS's `epubcfi(...)` to Readium Locator JSON on GET and back on PATCH, so
 * the local store always holds canonical Locator JSON. When the EPUB isn't cached the factory
 * returns null and the remote defers (leaves the row dirty) rather than writing a raw CFI.
 */
class AbsProgressRemoteFactory @Inject constructor(
    private val api: AbsSessionApi,
    private val libraryItemDao: LibraryItemDao,
    private val translatorFactory: EbookCfiTranslatorFactory,
) : ProgressRemoteFactory {

    override fun ebook(source: Source, token: String, itemId: String): ProgressRemote<String> =
        AbsEbookProgressRemote(
            api, source.url.value, token, source.insecureConnectionAllowed, itemId,
            translator = translatorFactory.forItem(source.id, itemId),
        ) {
            libraryItemDao.getById(source.id, itemId)?.readingProgress ?: 0f
        }

    override fun audio(source: Source, token: String, itemId: String): ProgressRemote<Double> =
        AbsAudioProgressRemote(api, source.url.value, token, source.insecureConnectionAllowed, itemId) {
            libraryItemDao.getById(source.id, itemId)?.audioDurationSec ?: 0.0
        }
}

package com.riffle.app.feature.reader

import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.LocalStore
import javax.inject.Inject

class EbookCfiTranslatorFactoryImpl @Inject constructor(
    @EpubDownloadsStore private val downloadsStore: LocalStore,
    @EpubCacheStore private val cacheStore: LocalStore,
) : EbookCfiTranslatorFactory {

    override fun forItem(sourceId: String, itemId: String): EbookCfiTranslator? {
        val file = downloadsStore.get(sourceId, itemId) ?: cacheStore.get(sourceId, itemId)
            ?: return null
        return EbookCfiTranslatorImpl(file)
    }
}

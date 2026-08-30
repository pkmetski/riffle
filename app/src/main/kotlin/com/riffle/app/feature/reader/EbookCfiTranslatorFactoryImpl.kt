package com.riffle.app.feature.reader

import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.LocalStore

class EbookCfiTranslatorFactoryImpl constructor(
    private val downloadsStore: LocalStore,
    private val cacheStore: LocalStore,
) : EbookCfiTranslatorFactory {

    override fun forItem(sourceId: String, itemId: String): EbookCfiTranslator? {
        val file = downloadsStore.get(sourceId, itemId) ?: cacheStore.get(sourceId, itemId)
            ?: return null
        return EbookCfiTranslatorImpl(file)
    }
}

package com.riffle.feature.source.ui

import com.riffle.feature.library.WebSourceLibraryItemUpserter
import org.koin.dsl.module

val sourceUiKoinModule = module {
    single<SourceUiStrings> { ComposeResourceSourceUiStrings }
    single<WebdavConnectionTester> { WebDavTargetConnectionTester(get()) }
    single<WebSourceLibraryItemUpserter> {
        val upserter = get<com.riffle.core.data.websource.WebSourceLibraryItemUpserter>()
        object : WebSourceLibraryItemUpserter {
            override suspend fun upsert(sourceId: String, item: com.riffle.core.catalog.CatalogItem) =
                upserter.upsert(sourceId, item)
        }
    }
}

package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.common.FileStore
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.LookupHistoryDao
import com.riffle.core.database.LookupHistoryEntity
import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryRepository
import com.riffle.core.domain.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class WordLookupRepositoryImpl constructor(
    dictionaryPackDao: DictionaryPackDao,
    fileStore: FileStore,
    private val lookupHistoryDao: LookupHistoryDao,
    private val packSqliteStore: DictionaryPackSqliteStore,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : PackStoreImpl(dictionaryPackDao, fileStore), DictionaryRepository {

    override suspend fun lookup(form: String, languageTag: String): List<DictionaryEntry> =
        withContext(dispatchers.io) {
            packSqliteStore.readerForLanguage(languageTag)?.query(form) ?: emptyList()
        }

    override suspend fun recordLookup(form: String, languageTag: String) {
        lookupHistoryDao.insert(
            LookupHistoryEntity(
                languageTag = languageTag,
                form = form,
                lookedUpAt = clock.nowMs(),
            ),
        )
        lookupHistoryDao.pruneOldest(languageTag)
    }

    override fun observeRecentLookups(languageTag: String, limit: Int): Flow<List<String>> =
        lookupHistoryDao.observeRecent(languageTag, limit)
}

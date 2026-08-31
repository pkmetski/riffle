package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.LookupHistoryDao
import com.riffle.core.database.LookupHistoryEntity
import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.DictionaryRepository
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackStore
import com.riffle.core.domain.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class WordLookupRepositoryImpl constructor(
    private val dictionaryPackDao: DictionaryPackDao,
    private val lookupHistoryDao: LookupHistoryDao,
    private val packSqliteStore: DictionaryPackSqliteStore,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : DictionaryRepository, PackStore {

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
            )
        )
        lookupHistoryDao.pruneOldest(languageTag)
    }

    override fun observeRecentLookups(languageTag: String, limit: Int): Flow<List<String>> =
        lookupHistoryDao.observeRecent(languageTag, limit)

    override fun observePackState(languageTag: String): Flow<DictionaryPackState> =
        dictionaryPackDao.observeForLanguage(languageTag).map { entity ->
            entity?.let { DictionaryPackState.valueOf(it.state) }
                ?: DictionaryPackState.NOT_INSTALLED
        }

    override fun observeInstalledPacks(): Flow<List<InstalledPack>> =
        dictionaryPackDao.observeAll().map { entities ->
            entities
                .filter { it.state == DictionaryPackState.INSTALLED.name }
                .map { e ->
                    InstalledPack(
                        languageTag = e.languageTag,
                        packVersion = e.packVersion,
                        installedAt = e.installedAt,
                        sizeBytes = e.sizeBytes,
                        attributionHtml = e.attributionHtml,
                        licenseUrl = e.licenseUrl,
                    )
                }
        }

    override suspend fun deleteInstalledPack(languageTag: String) {
        packSqliteStore.deletePackFile(languageTag)
        dictionaryPackDao.delete(languageTag)
    }
}

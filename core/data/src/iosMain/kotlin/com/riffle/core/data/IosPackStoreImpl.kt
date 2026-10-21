package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DICT_NAMESPACE = "dicts"

internal class IosPackStoreImpl(
    private val dictionaryPackDao: DictionaryPackDao,
    private val fileStore: FileStore,
) : PackStore {

    override fun observePackState(languageTag: String): Flow<DictionaryPackState> =
        dictionaryPackDao.observeForLanguage(languageTag).map { entity ->
            entity?.let { DictionaryPackState.valueOf(it.state) } ?: DictionaryPackState.NOT_INSTALLED
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
        dictionaryPackDao.delete(languageTag)
        IosItemFiles.delete(fileStore.resolve(DICT_NAMESPACE, "$languageTag.db"))
    }
}

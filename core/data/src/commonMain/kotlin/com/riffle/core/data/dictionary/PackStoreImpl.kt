package com.riffle.core.data.dictionary

import com.riffle.core.common.FileStore
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.FileSystem
import okio.Path.Companion.toPath

private const val DICT_NAMESPACE = "dicts"

open class PackStoreImpl(
    protected val dictionaryPackDao: DictionaryPackDao,
    protected val fileStore: FileStore,
    protected val fileSystem: FileSystem = FileSystem.SYSTEM,
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
        runCatching {
            fileSystem.delete(fileStore.resolve(DICT_NAMESPACE, "$languageTag.db").toPath())
        }
    }
}

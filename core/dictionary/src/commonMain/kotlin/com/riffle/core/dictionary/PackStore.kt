package com.riffle.core.dictionary

import kotlinx.coroutines.flow.Flow

interface PackStore {
    fun observePackState(languageTag: String): Flow<DictionaryPackState>
    fun observeInstalledPacks(): Flow<List<InstalledPack>>
    suspend fun deleteInstalledPack(languageTag: String)
}

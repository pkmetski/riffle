package com.riffle.core.data

import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// No-op PackStore for iOS. Dictionary pack installation is not yet supported on iOS;
// the store emits empty/not-installed state so the UI shows packs as available to download.
internal class IosPackStoreImpl : PackStore {
    override fun observePackState(languageTag: String): Flow<DictionaryPackState> =
        flowOf(DictionaryPackState.NOT_INSTALLED)

    override fun observeInstalledPacks(): Flow<List<InstalledPack>> = flowOf(emptyList())

    override suspend fun deleteInstalledPack(languageTag: String) = Unit
}

package com.riffle.app.feature.settings.dictionary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.dictionary.DictionaryPackScheduler
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.LanguageCatalog
import com.riffle.core.dictionary.LanguageCatalogEntry
import com.riffle.core.dictionary.PackStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DictionaryPacksViewModel @Inject constructor(
    private val packStore: PackStore,
    private val scheduler: DictionaryPackScheduler,
) : ViewModel() {

    val catalog: List<LanguageCatalogEntry> = LanguageCatalog.all

    val installedPacks: StateFlow<List<InstalledPack>> = run {
        val flow = MutableStateFlow<List<InstalledPack>>(emptyList())
        viewModelScope.launch {
            packStore.observeInstalledPacks().collect { flow.value = it }
        }
        flow.asStateFlow()
    }

    fun enqueueDownload(context: Context, entry: LanguageCatalogEntry) {
        scheduler.enqueueDownload(context, entry)
    }

    fun enqueueUpdate(context: Context, languageTag: String) {
        LanguageCatalog.entryFor(languageTag)?.let { scheduler.enqueueDownload(context, it) }
    }

    fun deleteInstalledPack(languageTag: String) {
        viewModelScope.launch { packStore.deleteInstalledPack(languageTag) }
    }
}

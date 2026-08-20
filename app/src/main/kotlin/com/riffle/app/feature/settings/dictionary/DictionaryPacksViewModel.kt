package com.riffle.app.feature.settings.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.library.DownloadManager
import com.riffle.app.feature.library.DownloadState
import com.riffle.core.data.dictionary.PackDownloader
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
    private val downloader: PackDownloader,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val catalog: List<LanguageCatalogEntry> = LanguageCatalog.all

    val installedPacks: StateFlow<List<InstalledPack>> = run {
        val flow = MutableStateFlow<List<InstalledPack>>(emptyList())
        viewModelScope.launch {
            packStore.observeInstalledPacks().collect { flow.value = it }
        }
        flow.asStateFlow()
    }

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloadManager.states

    fun enqueueDownload(entry: LanguageCatalogEntry) {
        downloadManager.start(downloadKey(entry.languageTag)) { onProgress ->
            if (downloader.download(entry, onProgress)) DownloadState.Downloaded
            else DownloadState.NotDownloaded
        }
    }

    fun enqueueUpdate(languageTag: String) {
        LanguageCatalog.entryFor(languageTag)?.let { enqueueDownload(it) }
    }

    fun cancelDownload(languageTag: String) {
        downloadManager.cancel(downloadKey(languageTag))
    }

    fun deleteInstalledPack(languageTag: String) {
        viewModelScope.launch { packStore.deleteInstalledPack(languageTag) }
        downloadManager.clear(downloadKey(languageTag))
    }

    companion object {
        fun downloadKey(languageTag: String) = "dict_$languageTag"
    }
}

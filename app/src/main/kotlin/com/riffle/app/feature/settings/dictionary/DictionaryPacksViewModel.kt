package com.riffle.app.feature.settings.dictionary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.dictionary.DictionaryPackScheduler
import com.riffle.core.data.dictionary.PackManifestFetcher
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackInfo
import com.riffle.core.dictionary.PackManifest
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
    private val packManifestFetcher: PackManifestFetcher,
    private val scheduler: DictionaryPackScheduler,
) : ViewModel() {

    val installedPacks: StateFlow<List<InstalledPack>> = run {
        val flow = MutableStateFlow<List<InstalledPack>>(emptyList())
        viewModelScope.launch {
            packStore.observeInstalledPacks().collect { flow.value = it }
        }
        flow.asStateFlow()
    }

    private val _manifest = MutableStateFlow<PackManifest?>(null)
    val manifest: StateFlow<PackManifest?> = _manifest.asStateFlow()

    private val _manifestError = MutableStateFlow(false)
    val manifestError: StateFlow<Boolean> = _manifestError.asStateFlow()

    init {
        refreshManifest()
    }

    fun refreshManifest() {
        viewModelScope.launch {
            _manifestError.value = false
            try {
                _manifest.value = packManifestFetcher.fetch()
            } catch (_: Exception) {
                _manifestError.value = true
            }
        }
    }

    fun enqueueDownload(context: Context, packInfo: PackInfo) {
        scheduler.enqueueDownload(context, packInfo)
    }
}

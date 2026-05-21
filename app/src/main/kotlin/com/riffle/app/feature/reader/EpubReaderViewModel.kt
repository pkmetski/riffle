package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

sealed class ReaderState {
    data object Loading : ReaderState()
    data class Ready(
        val publication: Publication,
        val title: String,
        val initialLocator: Locator?,
    ) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val epubRepository: EpubRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    init {
        viewModelScope.launch { openBook() }
    }

    private suspend fun openBook() {
        val item = libraryRepository.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> {
                val publication = openPublication(result.epubFile)
                if (publication == null) {
                    _state.value = ReaderState.Error("Failed to open EPUB")
                    return
                }
                val locator = result.lastPosition?.let { Locator.fromJSON(JSONObject(it)) }
                _state.value = ReaderState.Ready(
                    publication = publication,
                    title = item.title,
                    initialLocator = locator,
                )
            }
            is EpubOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
            EpubOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
        }
    }

    private suspend fun openPublication(file: File): Publication? {
        val url = AbsoluteUrl("file://${file.absolutePath}") ?: return null
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return null
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> null
        }
    }

    fun onPositionChanged(locator: Locator) {
        viewModelScope.launch {
            epubRepository.saveReadingPosition(itemId, locator.toJSON().toString())
        }
    }
}

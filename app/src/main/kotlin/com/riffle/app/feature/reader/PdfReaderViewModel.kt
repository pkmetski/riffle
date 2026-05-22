package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.PdfRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val pdfRepository: PdfRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch { openBook() }
    }

    private suspend fun openBook() {
        val item = libraryRepository.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = pdfRepository.openPdf(item)) {
            is PdfOpenResult.Success -> {
                val publicationResult = openPublication(result.pdfFile)
                val publication = publicationResult.getOrElse {
                    _state.value = ReaderState.Error("Failed to open PDF: ${it.message}")
                    return
                }
                val locator = result.lastPosition?.let { Locator.fromJSON(JSONObject(it)) }
                _state.value = ReaderState.Ready(
                    publication = publication,
                    title = item.title,
                    initialLocator = locator,
                )
            }
            is PdfOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
            PdfOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
        }
    }

    private suspend fun openPublication(file: File): Result<Publication> {
        val url = AbsoluteUrl("file://${file.absolutePath}")
            ?: return Result.failure(IllegalArgumentException("Invalid file path: ${file.absolutePath}"))
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return Result.failure(Exception(r.value.message))
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> Result.success(r.value)
            is Try.Failure -> Result.failure(Exception(r.value.message))
        }
    }

    private val _currentPage = MutableStateFlow<Int?>(null)
    val currentPage: StateFlow<Int?> = _currentPage

    fun onPageChanged(locator: Locator) {
        _currentPage.value = locator.locations.position
        viewModelScope.launch {
            pdfRepository.saveReadingPosition(itemId, locator.toJSON().toString())
        }
    }
}

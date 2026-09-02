package com.riffle.app.feature.library.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.urlDecode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val name: String = "",
    val items: List<LibraryItem> = emptyList(),
    val isLoading: Boolean = true,
    val deleted: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistsRepository: PlaylistsRepository,
    private val libraryObserver: LibraryObserver,
    sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""
    val playlistId: String = savedStateHandle.get<String>("playlistId") ?: ""
    private val initialName: String =
        (savedStateHandle.get<String>("playlistName") ?: "").urlDecode()

    private sealed interface PlaylistLoad {
        data object Loading : PlaylistLoad
        data class Present(val playlist: CatalogPlaylist) : PlaylistLoad
        data object Deleted : PlaylistLoad
    }

    private val loadFlow = MutableStateFlow<PlaylistLoad>(PlaylistLoad.Loading)

    private val _snackbarEvents = Channel<String>(Channel.BUFFERED)
    val snackbarEvents: Flow<String> = _snackbarEvents.receiveAsFlow()

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        loadFlow,
        libraryObserver.observeLibraryItems(libraryId),
    ) { load, libraryItems ->
        when (load) {
            PlaylistLoad.Loading -> PlaylistDetailUiState(name = initialName, isLoading = true)
            PlaylistLoad.Deleted -> PlaylistDetailUiState(name = initialName, isLoading = false, deleted = true)
            is PlaylistLoad.Present -> {
                val byId = libraryItems.associateBy { it.id }
                val ordered = load.playlist.itemIds.mapNotNull { byId[it] }
                PlaylistDetailUiState(
                    name = load.playlist.name,
                    items = ordered,
                    isLoading = false,
                )
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaylistDetailUiState(name = initialName),
    )

    var authToken: String = ""
        private set

    init {
        viewModelScope.launch {
            val active = sourceRepository.getActive()
            if (active != null) authToken = tokenStorage.getToken(active.id) ?: ""
            reload()
        }
    }

    fun removeItem(itemId: String) {
        viewModelScope.launch {
            val ok = playlistsRepository.removeItemFromPlaylist(libraryId, playlistId, itemId)
            if (ok) {
                reload()
                _snackbarEvents.trySend("Removed from playlist")
            } else {
                _snackbarEvents.trySend("Couldn't remove from playlist")
            }
        }
    }

    private suspend fun reload() {
        val fetched = playlistsRepository.getPlaylist(libraryId, playlistId)
        loadFlow.value = if (fetched != null) {
            PlaylistLoad.Present(fetched)
        } else if (loadFlow.value is PlaylistLoad.Loading) {
            PlaylistLoad.Deleted
        } else {
            PlaylistLoad.Deleted
        }
    }
}

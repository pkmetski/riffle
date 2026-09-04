package com.riffle.shared.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface LibraryItemDetailUiState {
    data object Loading : LibraryItemDetailUiState
    data class Ready(
        val item: LibraryItem,
        val authToken: String,
        val isInToRead: Boolean = false,
        val isOffline: Boolean = false,
    ) : LibraryItemDetailUiState
    data object Error : LibraryItemDetailUiState
}

class LibraryItemDetailViewModel(
    val itemId: String,
    val sourceId: String?,
    private val libraryObserver: LibraryObserver,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val toReadRepository: ToReadRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryItemDetailUiState>(LibraryItemDetailUiState.Loading)
    val uiState: StateFlow<LibraryItemDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            val source = sourceId?.let { id ->
                sourceRepository.getById(id) ?: sourceRepository.getActive()
            } ?: sourceRepository.getActive()
            val token = source?.let { tokenStorage.getToken(it.id) } ?: ""
            val item = sourceId
                ?.let { libraryObserver.getItem(it, itemId) }
                ?: libraryObserver.getItem(itemId)
            _uiState.value = if (item != null) {
                val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)
                LibraryItemDetailUiState.Ready(
                    item = item,
                    authToken = token,
                    isInToRead = isInToRead,
                    isOffline = !connectivityObserver.isOnline.value,
                )
            } else {
                LibraryItemDetailUiState.Error
            }

            connectivityObserver.isOnline
                .onEach { online ->
                    val current = _uiState.value
                    if (current is LibraryItemDetailUiState.Ready) {
                        _uiState.value = current.copy(isOffline = !online)
                    }
                }
                .launchIn(viewModelScope)
        }

        val itemFlow = sourceId
            ?.let { libraryObserver.observeItem(it, itemId) }
            ?: libraryObserver.observeItem(itemId)
        itemFlow
            .onEach { latest ->
                if (latest == null) return@onEach
                val current = _uiState.value
                if (current is LibraryItemDetailUiState.Ready && current.item != latest) {
                    _uiState.value = current.copy(item = latest)
                }
            }
            .launchIn(viewModelScope)
    }

    fun toggleToRead() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val wasInToRead = current.isInToRead
        _uiState.value = current.copy(isInToRead = !wasInToRead)
        viewModelScope.launch {
            val ok = if (wasInToRead) {
                toReadRepository.removeFromToRead(current.item.id, current.item.libraryId)
            } else {
                toReadRepository.addToToRead(current.item.id, current.item.libraryId)
            }
            if (!ok) {
                val now = _uiState.value as? LibraryItemDetailUiState.Ready ?: return@launch
                _uiState.value = now.copy(isInToRead = wasInToRead)
            }
        }
    }
}

package com.riffle.app.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.ReleaseInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChangelogUiState {
    data object Loading : ChangelogUiState
    data class Loaded(val releases: List<ReleaseInfo>) : ChangelogUiState
}

@HiltViewModel
class ChangelogViewModel @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ChangelogUiState>(ChangelogUiState.Loading)
    val state: StateFlow<ChangelogUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = ChangelogUiState.Loaded(appUpdateRepository.listReleasesSince(0))
        }
    }
}

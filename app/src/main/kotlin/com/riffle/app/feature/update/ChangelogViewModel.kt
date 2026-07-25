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

@HiltViewModel
class ChangelogViewModel @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository,
) : ViewModel() {
    private val _releases = MutableStateFlow<List<ReleaseInfo>>(emptyList())
    val releases: StateFlow<List<ReleaseInfo>> = _releases.asStateFlow()

    init {
        viewModelScope.launch {
            _releases.value = appUpdateRepository.listReleasesSince(0)
        }
    }
}

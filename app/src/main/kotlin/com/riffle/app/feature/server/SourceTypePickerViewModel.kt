package com.riffle.app.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs [SourceTypePickerScreen] with the "is LocalFiles already installed?" flag. LocalFiles is a
 * device singleton — one row max — so after the first install the picker hides its tile and points
 * the user at the Settings folder-management row to add another folder to that existing source.
 */
@HiltViewModel
class SourceTypePickerViewModel @Inject constructor(
    sourceRepository: SourceRepository,
) : ViewModel() {

    val hasLocalFilesSource: StateFlow<Boolean> = sourceRepository.observeAll()
        .map { sources -> sources.any { it.type == SourceType.LOCAL_FILES } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)
}

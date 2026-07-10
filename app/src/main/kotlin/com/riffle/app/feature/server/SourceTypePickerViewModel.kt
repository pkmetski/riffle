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

    // Default to true (tile hidden) so a user who already has a LocalFiles source doesn't see
    // the tile flash into view for a frame before the flow's first emission removes it. First-run
    // users on a fresh device see the ABS card immediately and the LocalFiles card fades in on
    // the first emission — a less-jarring order than "tile appears, then vanishes".
    val hasLocalFilesSource: StateFlow<Boolean> = sourceRepository.observeAll()
        .map { sources -> sources.any { it.type == SourceType.LOCAL_FILES } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)
}

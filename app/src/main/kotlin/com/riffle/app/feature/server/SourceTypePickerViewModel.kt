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
 * Backs [SourceTypePickerScreen] with "is X already installed?" flags for the credential-less
 * source types (LocalFiles, Chitanka). Both are device singletons — one row max each — so after
 * the first install the picker hides that tile. For LocalFiles, adding another folder is a
 * dedicated action in Settings; for Chitanka there's nothing more to configure.
 *
 * The rule "at most one instance" applies to every source type that requires no login: without
 * credentials there's nothing to disambiguate a second row from the first, so a duplicate would
 * be either a silent no-op or a confusing duplicate library entry.
 */
@HiltViewModel
class SourceTypePickerViewModel @Inject constructor(
    sourceRepository: SourceRepository,
) : ViewModel() {

    // Default to true (tile hidden) so a user who already has these sources doesn't see the
    // tile flash into view for a frame before the flow's first emission removes it. First-run
    // users on a fresh device see the ABS card immediately and the singleton cards fade in on
    // the first emission — a less-jarring order than "tile appears, then vanishes".
    val hasLocalFilesSource: StateFlow<Boolean> = sourceRepository.observeAll()
        .map { sources -> sources.any { it.type == SourceType.LOCAL_FILES } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)

    val hasChitankaSource: StateFlow<Boolean> = sourceRepository.observeAll()
        .map { sources -> sources.any { it.type == SourceType.CHITANKA } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)
}

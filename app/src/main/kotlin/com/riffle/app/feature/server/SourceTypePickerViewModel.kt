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
 * Backs [SourceTypePickerScreen] with the set of already-installed [SourceType]s. The picker
 * screen filters cards from `WebSourceDescriptors.all`, hiding descriptor-`isSingleton` types
 * whose entry is present in [installedTypes]. Post-ADR-0044: adding a new singleton source is a
 * descriptor registration; no per-source `has<X>Source` StateFlow to author here.
 *
 * A source type is a "singleton" per its `WebSourceDescriptor.isSingleton` flag — every
 * credential-less source is one (LocalFiles, Chitanka, Gutenberg, …). Rule: without login there
 * is nothing to disambiguate a second row from the first, so a duplicate would be a silent no-op
 * or a confusing duplicate library entry.
 */
@HiltViewModel
class SourceTypePickerViewModel @Inject constructor(
    sourceRepository: SourceRepository,
) : ViewModel() {

    /**
     * Default to `SourceType.values()` (every singleton card hidden) so a returning user who
     * already has these sources doesn't see cards flash into view for a frame before the flow's
     * first emission removes them. First-run users see the ABS (multi-server) card immediately
     * and singleton cards fade in on the first emission — a less-jarring order than "card
     * appears, then vanishes".
     */
    val installedTypes: StateFlow<Set<SourceType>> = sourceRepository.observeAll()
        .map { sources -> sources.map { it.type }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SourceType.values().toSet(),
        )
}

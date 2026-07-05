package com.riffle.app.feature.reader.controllers

import com.riffle.app.feature.reader.session.OrchestratorScope
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.cadence.CadenceState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Derives `keepScreenOn` from the wakeLock preference combined with the two hands-free running
 * states — Auto-Scroll and Cadence (issue #403). When either is Running, the screen stays on
 * regardless of preference: a sleeping screen would visibly break a hands-free session.
 *
 * MUST NOT import org.readium.*, android.webkit.*, or ContinuousReaderView.
 */
class WakeLockController @AssistedInject constructor(
    @Assisted private val scope: OrchestratorScope,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    @Assisted private val autoScrollState: StateFlow<AutoScrollState>,
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, autoScrollState: StateFlow<AutoScrollState>): WakeLockController
    }

    /**
     * Cadence state, plumbed in after construction via [attachCadenceState] because the reader
     * VM builds this controller before the CadenceController field is available. The default
     * [MutableStateFlow] keeps the combine happy in tests / setups that never attach.
     */
    private val cadenceState = MutableStateFlow<CadenceState>(CadenceState.Idle)

    fun attachCadenceState(state: StateFlow<CadenceState>) {
        scope.launch { state.collect { cadenceState.value = it } }
    }

    val keepScreenOn: StateFlow<Boolean> = combine(
        wakeLockPreferencesStore.keepScreenOn,
        autoScrollState,
        cadenceState,
    ) { pref, scroll, cadence ->
        pref || scroll is AutoScrollState.Running || cadence is CadenceState.Running
    }.stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setKeepScreenOn(value: Boolean) {
        wakeLockPreferencesStore.setKeepScreenOn(value)
    }
}

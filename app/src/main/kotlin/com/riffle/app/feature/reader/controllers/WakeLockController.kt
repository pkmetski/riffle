package com.riffle.app.feature.reader.controllers

import com.riffle.app.feature.reader.session.OrchestratorScope
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.autoscroll.AutoScrollState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Derives `keepScreenOn` from the wakeLock preference combined with autoScroll running state.
 * When autoScroll is active, the screen stays on regardless of preference (hands-free reading).
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

    val keepScreenOn: StateFlow<Boolean> = combine(
        wakeLockPreferencesStore.keepScreenOn,
        autoScrollState,
    ) { pref, scroll ->
        pref || scroll is AutoScrollState.Running
    }.stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setKeepScreenOn(value: Boolean) {
        wakeLockPreferencesStore.setKeepScreenOn(value)
    }
}

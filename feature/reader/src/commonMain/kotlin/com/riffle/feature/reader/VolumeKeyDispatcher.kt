package com.riffle.feature.reader

import com.riffle.core.domain.VolumeKeyPreferencesStore
import kotlinx.coroutines.flow.SharedFlow

/**
 * Stateless dispatcher for volume-key navigation preferences and events.
 *
 * Holds the configuration (enabled, inverted) and forwards events from the
 * volume navigation controller. No business logic — pure passthrough.
 */
class VolumeKeyDispatcher constructor(
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,
    private val volumeNavigationController: VolumeNavigationController,
) {
    val volumeKeyNavigationEnabled =
        volumeKeyPreferencesStore.volumeKeyNavigationEnabled

    val invertVolumeKeys =
        volumeKeyPreferencesStore.invertVolumeKeys

    val volumeNavEvents: SharedFlow<VolumeNavEvent> =
        volumeNavigationController.events

    suspend fun setVolumeKeyNavigationEnabled(enabled: Boolean) {
        volumeKeyPreferencesStore.setVolumeKeyNavigationEnabled(enabled)
    }

    suspend fun setInvertVolumeKeys(invert: Boolean) {
        volumeKeyPreferencesStore.setInvertVolumeKeys(invert)
    }
}

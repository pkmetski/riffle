package com.riffle.feature.reader

import com.riffle.core.domain.VolumeKeyPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeKeyDispatcherTest {

    @Test
    fun `volumeKeyNavigationEnabled mirrors store`() = runTest {
        val store = FakeVolumeKeyPreferencesStore(navigationEnabled = true)
        val controller = VolumeNavigationController()
        val dispatcher = VolumeKeyDispatcher(store, controller)

        var value = false
        dispatcher.volumeKeyNavigationEnabled.collect {
            value = it
        }
        assertTrue(value)
    }

    @Test
    fun `invertVolumeKeys mirrors store`() = runTest {
        val store = FakeVolumeKeyPreferencesStore(invertKeys = true)
        val controller = VolumeNavigationController()
        val dispatcher = VolumeKeyDispatcher(store, controller)

        var value = false
        dispatcher.invertVolumeKeys.collect {
            value = it
        }
        assertTrue(value)
    }

    @Test
    fun `volumeNavEvents forwards from volumeNavigationController`() = runTest {
        val store = FakeVolumeKeyPreferencesStore()
        val controller = VolumeNavigationController()
        val dispatcher = VolumeKeyDispatcher(store, controller)

        val event = VolumeNavEvent.Forward
        controller.emit(event)

        // Verify the event is accessible through the dispatcher
        assertEquals(controller.events, dispatcher.volumeNavEvents)
    }

    private class FakeVolumeKeyPreferencesStore(
        val navigationEnabled: Boolean = false,
        val invertKeys: Boolean = false,
    ) : VolumeKeyPreferencesStore {
        override val volumeKeyNavigationEnabled: Flow<Boolean> = flowOf(navigationEnabled)
        override val invertVolumeKeys: Flow<Boolean> = flowOf(invertKeys)

        override suspend fun setVolumeKeyNavigationEnabled(enabled: Boolean) {}
        override suspend fun setInvertVolumeKeys(invert: Boolean) {}
    }
}

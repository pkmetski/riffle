package com.riffle.app.feature.reader.controllers

import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockControllerTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private class FakeWakeLockPreferencesStore : WakeLockPreferencesStore {
        private val _keepScreenOn = MutableStateFlow(true)
        override val keepScreenOn = _keepScreenOn

        override suspend fun setKeepScreenOn(value: Boolean) {
            _keepScreenOn.value = value
        }
    }

    @Test
    fun `keepScreenOn true when prefs allow and autoScroll is not running`() = runTest {
        val prefs = FakeWakeLockPreferencesStore()
        prefs.setKeepScreenOn(true)
        val autoScrollState = MutableStateFlow<AutoScrollState>(AutoScrollState.Idle)

        val controller = WakeLockController(testScope, prefs, autoScrollState)

        assertTrue(controller.keepScreenOn.value)
    }

    @Test
    fun `keepScreenOn true when autoScroll is running regardless of prefs`() {
        val prefs = FakeWakeLockPreferencesStore()
        runBlocking {
            prefs.setKeepScreenOn(false)
        }
        val autoScrollState = MutableStateFlow<AutoScrollState>(
            AutoScrollState.Running(AutoScrollSpeed.Default)
        )

        val controller = WakeLockController(testScope, prefs, autoScrollState)

        assertTrue(controller.keepScreenOn.value)
    }

    @Test
    fun `keepScreenOn false when prefs deny and autoScroll not running`() = runTest {
        val prefs = FakeWakeLockPreferencesStore()
        prefs.setKeepScreenOn(false)
        val autoScrollState = MutableStateFlow<AutoScrollState>(AutoScrollState.Idle)

        val controller = WakeLockController(testScope, prefs, autoScrollState)

        assertFalse(controller.keepScreenOn.value)
    }

    @Test
    fun `setKeepScreenOn delegates to store`() = runTest {
        val prefs = FakeWakeLockPreferencesStore()
        val autoScrollState = MutableStateFlow<AutoScrollState>(AutoScrollState.Idle)

        val controller = WakeLockController(testScope, prefs, autoScrollState)

        controller.setKeepScreenOn(false)
        assertFalse(prefs.keepScreenOn.value)

        controller.setKeepScreenOn(true)
        assertTrue(prefs.keepScreenOn.value)
    }
}

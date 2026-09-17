package com.riffle.core.data

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.ReadingSpeedTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fake [PreferenceStore] that stores a single in-memory value — platform-independent. */
private class FakePreferenceStore<T>(initial: T) : PreferenceStore<T> {
    private val _state = MutableStateFlow(initial)
    override val flow: Flow<T> = _state
    override suspend fun update(value: T) { _state.value = value }
}

class PreferenceBackedStoresTest {

    @Test
    fun `appThemeStore defaults to System and round-trips`() = runTest {
        val store = appThemeStore(FakePreferenceStore(AppTheme.System))
        assertEquals(AppTheme.System, store.appTheme.first())
        store.setAppTheme(AppTheme.Dark)
        assertEquals(AppTheme.Dark, store.appTheme.first())
        store.setAppTheme(AppTheme.Light)
        assertEquals(AppTheme.Light, store.appTheme.first())
    }

    @Test
    fun `readingSpeedStore defaults to tracker default and round-trips`() = runTest {
        val store = readingSpeedStore(FakePreferenceStore(ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION))
        assertEquals(ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION, store.speedSecPerPosition.first())
        store.updateSpeed(120.0)
        assertEquals(120.0, store.speedSecPerPosition.first())
    }

    @Test
    fun `wakeLockPreferencesStore defaults to true and round-trips`() = runTest {
        val store = wakeLockPreferencesStore(FakePreferenceStore(true))
        assertEquals(true, store.keepScreenOn.first())
        store.setKeepScreenOn(false)
        assertEquals(false, store.keepScreenOn.first())
        store.setKeepScreenOn(true)
        assertEquals(true, store.keepScreenOn.first())
    }
}

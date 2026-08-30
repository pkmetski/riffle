package com.riffle.app.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.domain.LibraryObserver
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Smoke test that verifies the Koin ViewModel module graph initializes correctly and the
 * Hilt bridge exposes at least one known dep. Full graph verification (checkModules) will be
 * added in #737 when the data layer is fully Koin-native and no bridge is needed.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class KoinModulesTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun koinBridgeResolvesLibraryObserver() {
        // Application.onCreate() already started Koin with riffleViewModelKoinModules().
        // Resolving a bridged singleton confirms the Hilt entry point and Koin module wiring are
        // both healthy. If the bridge entry point is missing a binding this throws at runtime.
        val koin = GlobalContext.get()
        val observer = koin.get<LibraryObserver>()
        assertNotNull(observer)
    }

    @Test
    fun koinViewModelModulesAreNonEmpty() {
        val modules = riffleViewModelKoinModules()
        assert(modules.isNotEmpty()) { "riffleViewModelKoinModules() must not be empty" }
    }
}

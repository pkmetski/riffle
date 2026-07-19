package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.app.feature.reader.session.FormattingSession
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferencesStoreProvider
import com.riffle.core.models.FormattingScope
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PdfReaderViewModel cannot be constructed directly in JVM unit tests — Readium's PDF adapter
 * types (Locator, Publication, AbsoluteUrl, etc.) touch android.net.Uri, which isn't available
 * off-device (same limitation documented for EpubReaderViewModelTest). This test instead
 * exercises the FormattingSession delegation shape that PdfReaderViewModel wires verbatim from
 * EpubReaderViewModel's pattern — verifying updateFormatting persists to the book override store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfReaderViewModelFormattingTest {

    private class FakeFormattingPreferencesStore(
        initial: FormattingPreferences = FormattingPreferences(),
    ) : FormattingPreferencesStore {
        private val _flow = MutableStateFlow(initial)
        override val preferences: Flow<FormattingPreferences> = _flow
        override suspend fun update(preferences: FormattingPreferences) { _flow.value = preferences }
        override suspend fun setCadencePlatformSupported(supported: Boolean) {
            _flow.value = _flow.value.copy(cadencePlatformSupported = supported)
        }
    }

    private class FakeBookFormattingPreferencesStore : BookFormattingPreferencesStore {
        private val saved = mutableMapOf<Pair<String, FormattingScope>, BookFormattingOverrides>()
        override suspend fun load(itemId: String, scope: FormattingScope): BookFormattingOverrides =
            saved[itemId to scope] ?: BookFormattingOverrides()
        override suspend fun save(itemId: String, scope: FormattingScope, overrides: BookFormattingOverrides) {
            saved[itemId to scope] = overrides
        }
        override suspend fun clear(itemId: String, scope: FormattingScope) { saved.remove(itemId to scope) }
        fun captured(itemId: String, scope: FormattingScope = FormattingScope.FullBook): BookFormattingOverrides? =
            saved[itemId to scope]
    }

    private class FakeWakeLockPreferencesStore : WakeLockPreferencesStore {
        private val _flow = MutableStateFlow(false)
        override val keepScreenOn: Flow<Boolean> = _flow
        override suspend fun setKeepScreenOn(value: Boolean) { _flow.value = value }
    }

    private class FakeListeningPreferencesStore : ListeningPreferencesStore {
        override val defaultPlaybackSpeed: Flow<Float> = MutableStateFlow(1.0f)
        override val skipIntervalSeconds: Flow<Int> = MutableStateFlow(30)
        override val rewindIntervalSeconds: Flow<Int> = MutableStateFlow(15)
        override val rewindOnResumeSeconds: Flow<Int> = MutableStateFlow(0)
        override suspend fun setDefaultPlaybackSpeed(speed: Float) = Unit
        override suspend fun setSkipIntervalSeconds(seconds: Int) = Unit
        override suspend fun setRewindIntervalSeconds(seconds: Int) = Unit
        override suspend fun setRewindOnResumeSeconds(seconds: Int) = Unit
    }

    private class FakeAppearanceCoordinator(
        initial: ResolvedAppearance = ResolvedAppearance(
            appChrome = ChromeTheme.Light,
            readerTheme = ConcreteReaderTheme.Light,
            isSystemDark = false,
        ),
    ) : AppearanceCoordinator {
        private val _flow = MutableStateFlow(initial)
        override val resolved: StateFlow<ResolvedAppearance> = _flow
        override fun setSystemDark(isDark: Boolean) = Unit
    }

    private data class Fixture(
        val session: FormattingSession,
        val bookStore: FakeBookFormattingPreferencesStore,
        val autoScrollController: AutoScrollController,
        val scope: CoroutineScope,
    )

    private fun formattingSessionFixture(): Fixture {
        val dispatcher = UnconfinedTestDispatcher()
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val scope = CoroutineScope(dispatcher)
        val bookStore = FakeBookFormattingPreferencesStore()
        val session = FormattingSession(
            scope = scope,
            formattingPreferencesStoreProvider = object : FormattingPreferencesStoreProvider {
                private val store = FakeFormattingPreferencesStore()
                override fun store(scope: FormattingScope) = store
            },
            bookFormattingPreferencesStore = bookStore,
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = FakeAppearanceCoordinator(),
        )
        return Fixture(session, bookStore, autoScrollController, scope)
    }

    @Test
    fun `updateFormatting persists to book override store`() = runTest {
        val fixture = formattingSessionFixture()
        try {
            fixture.session.bindToBook("book-1")
            fixture.session.updateFormatting("book-1", FormattingPreferences(margins = 2.0f))
            assertEquals(2.0f, fixture.bookStore.captured("book-1")?.margins)
        } finally {
            fixture.autoScrollController.release()
            fixture.scope.cancel()
        }
    }
}

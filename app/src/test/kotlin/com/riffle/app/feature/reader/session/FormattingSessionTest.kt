package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FormattingSessionTest {

    // --- Fakes ---

    private class FakeFormattingPreferencesStore(
        initial: FormattingPreferences = FormattingPreferences(),
    ) : FormattingPreferencesStore {
        private val _flow = MutableStateFlow(initial)
        override val preferences: Flow<FormattingPreferences> = _flow
        override suspend fun update(preferences: FormattingPreferences) { _flow.value = preferences }
        fun set(p: FormattingPreferences) { _flow.value = p }
    }

    private class FakeBookFormattingPreferencesStore : BookFormattingPreferencesStore {
        val saved = mutableMapOf<String, BookFormattingOverrides>()
        private var toReturn = BookFormattingOverrides()
        fun willReturn(o: BookFormattingOverrides) { toReturn = o }
        override suspend fun load(itemId: String): BookFormattingOverrides = saved[itemId] ?: toReturn
        override suspend fun save(itemId: String, overrides: BookFormattingOverrides) { saved[itemId] = overrides }
        override suspend fun clear(itemId: String) { saved.remove(itemId) }
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

    /**
     * Drives the resolved appearance manually so tests can simulate boundary crossings without
     * spinning the real coordinator's timer. Auto-theme behaviour end-to-end is covered by the
     * AppearanceCoordinatorImpl test.
     */
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
        fun set(value: ResolvedAppearance) { _flow.value = value }
    }

    /**
     * Creates a FormattingSession backed by [UnconfinedTestDispatcher] so coroutines run eagerly.
     * The returned [AutoScrollController] must have [AutoScrollController.release] called before the
     * test ends when auto-scroll is started, to prevent its 16ms ticker from blocking runTest cleanup.
     */
    private fun makeEager(
        globalPrefs: FormattingPreferences = FormattingPreferences(),
        bookOverrides: BookFormattingOverrides = BookFormattingOverrides(),
        fakeGlobal: FakeFormattingPreferencesStore = FakeFormattingPreferencesStore(globalPrefs),
        fakeBook: FakeBookFormattingPreferencesStore = FakeBookFormattingPreferencesStore().also {
            it.willReturn(bookOverrides)
        },
        fakeAppearance: FakeAppearanceCoordinator = FakeAppearanceCoordinator(),
    ): Bundle {
        val dispatcher = UnconfinedTestDispatcher()
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            formattingPreferencesStore = fakeGlobal,
            bookFormattingPreferencesStore = fakeBook,
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = fakeAppearance,
        )
        return Bundle(session, fakeGlobal, fakeBook, fakeAppearance, autoScrollController, sessionScope)
    }

    private data class Bundle(
        val session: FormattingSession,
        val globalStore: FakeFormattingPreferencesStore,
        val bookStore: FakeBookFormattingPreferencesStore,
        val appearance: FakeAppearanceCoordinator,
        val autoScrollController: AutoScrollController,
        val sessionScope: kotlinx.coroutines.CoroutineScope,
    )

    // 1. effectiveFormattingPreferences emits global when no book overrides
    @Test
    fun `effectiveFormattingPreferences emits global when no book overrides`() = runTest {
        val global = FormattingPreferences(fontSize = 1.5f)
        val (session, _, _, _, _, scope) = makeEager(globalPrefs = global)
        try {
            session.bindToBook("item1")
            assertEquals(1.5f, session.effectiveFormattingPreferences.value.fontSize)
        } finally {
            scope.cancel()
        }
    }

    // 2. effectiveFormattingPreferences merges book overrides on top
    @Test
    fun `effectiveFormattingPreferences merges book overrides on top of global`() = runTest {
        val global = FormattingPreferences(fontSize = 1.0f, theme = ReaderTheme.Light)
        val overrides = BookFormattingOverrides(fontSize = 2.0f)
        val (session, _, _, _, _, scope) = makeEager(globalPrefs = global, bookOverrides = overrides)
        try {
            session.bindToBook("item1")
            assertEquals(2.0f, session.effectiveFormattingPreferences.value.fontSize)
            assertEquals(ReaderTheme.Light, session.effectiveFormattingPreferences.value.theme)
        } finally {
            scope.cancel()
        }
    }

    // 3. updateFormatting persists to bookFormattingPreferencesStore
    @Test
    fun `updateFormatting persists book overrides to store`() = runTest {
        val (session, _, bookStore, _, _, scope) = makeEager()
        try {
            session.bindToBook("item1")
            val newPrefs = FormattingPreferences(fontSize = 1.8f)
            session.updateFormatting("item1", newPrefs)
            assertTrue(bookStore.saved.containsKey("item1"))
        } finally {
            scope.cancel()
        }
    }

    // 4. resetToGlobalDefaults clears book overrides
    @Test
    fun `resetToGlobalDefaults clears book overrides from store`() = runTest {
        val overrides = BookFormattingOverrides(fontSize = 2.0f)
        val (session, _, bookStore, _, _, scope) = makeEager(bookOverrides = overrides)
        try {
            session.bindToBook("item1")
            assertTrue(session.hasBookOverrides.value)
            session.resetToGlobalDefaults("item1")
            assertFalse(session.hasBookOverrides.value)
            assertFalse(bookStore.saved.containsKey("item1"))
        } finally {
            scope.cancel()
        }
    }

    // 5. Auto theme uses the coordinator's resolved reader theme.
    @Test
    fun `auto theme is resolved from appearance coordinator`() = runTest {
        val global = FormattingPreferences(theme = ReaderTheme.Auto)
        val appearance = FakeAppearanceCoordinator(
            initial = ResolvedAppearance(
                appChrome = ChromeTheme.Light,
                readerTheme = ConcreteReaderTheme.Light,
                isSystemDark = false,
            ),
        )
        val (session, _, _, _, _, scope) = makeEager(globalPrefs = global, fakeAppearance = appearance)
        try {
            session.bindToBook("item1")
            assertEquals(ReaderTheme.Light, session.effectiveFormattingPreferences.value.theme)

            // Simulate the coordinator emitting a boundary tick that flips to night.
            appearance.set(
                ResolvedAppearance(
                    appChrome = ChromeTheme.Light,
                    readerTheme = ConcreteReaderTheme.Dark,
                    isSystemDark = false,
                ),
            )
            assertEquals(ReaderTheme.Dark, session.effectiveFormattingPreferences.value.theme)
        } finally {
            scope.cancel()
        }
    }

    // 6. auto-scroll stops when playback isPlaying transitions to true
    @Test
    fun `auto-scroll stops when onPlaybackStateChanged called with isPlaying true`() = runTest {
        val (session, _, _, _, autoScrollController, scope) = makeEager()
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)

            session.onPlaybackStateChanged(isPlaying = true)
            assertTrue(autoScrollController.state.value is AutoScrollState.Idle)
        } finally {
            autoScrollController.release()
            scope.cancel()
        }
    }

    // 7. setAutoScrollPaused pauses then resumeAutoScrollIfPaused resumes
    @Test
    fun `setAutoScrollPaused pauses then resumeAutoScrollIfPaused resumes`() = runTest {
        val (session, _, _, _, autoScrollController, scope) = makeEager()
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)

            session.setAutoScrollPaused(paused = true, cause = com.riffle.core.domain.autoscroll.PauseCause.PanelOpen)
            assertTrue(autoScrollController.state.value is AutoScrollState.Paused)

            session.resumeAutoScrollIfPaused()
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)
        } finally {
            autoScrollController.release()
            scope.cancel()
        }
    }

    // 8. formattingPreferencesReady gates emission until prefs load
    @Test
    fun `formattingPreferencesReady is false before bindToBook and true after`() = runTest {
        val (session, _, _, _, _, scope) = makeEager()
        try {
            assertFalse(session.formattingPreferencesReady.value)
            session.bindToBook("item1")
            assertTrue(session.formattingPreferencesReady.value)
        } finally {
            scope.cancel()
        }
    }

    // 9. hasBookOverrides true when any book pref set
    @Test
    fun `hasBookOverrides is true when book has non-default overrides`() = runTest {
        val overrides = BookFormattingOverrides(theme = ReaderTheme.Dark)
        val (session, _, _, _, _, scope) = makeEager(bookOverrides = overrides)
        try {
            session.bindToBook("item1")
            assertTrue(session.hasBookOverrides.value)
        } finally {
            scope.cancel()
        }
    }

    // 10. bindToBook re-issues the override stream for a new book
    @Test
    fun `bindToBook applies overrides for each book`() = runTest {
        val fakeGlobal = FakeFormattingPreferencesStore(FormattingPreferences(fontSize = 1.0f))
        val fakeBook = FakeBookFormattingPreferencesStore()
        fakeBook.saved["book-a"] = BookFormattingOverrides(fontSize = 1.5f)
        fakeBook.saved["book-b"] = BookFormattingOverrides(fontSize = 2.0f)
        val (session, _, _, _, _, scope) = makeEager(fakeGlobal = fakeGlobal, fakeBook = fakeBook)
        try {
            session.bindToBook("book-a")
            assertEquals(1.5f, session.effectiveFormattingPreferences.value.fontSize)

            session.bindToBook("book-b")
            assertEquals(2.0f, session.effectiveFormattingPreferences.value.fontSize)
        } finally {
            scope.cancel()
        }
    }

    // 11. onBookClosed clears formattingPreferencesReady
    @Test
    fun `onBookClosed sets formattingPreferencesReady to false`() = runTest {
        val (session, _, _, _, _, scope) = makeEager()
        try {
            session.bindToBook("item1")
            assertTrue(session.formattingPreferencesReady.value)

            session.onBookClosed()
            assertFalse(session.formattingPreferencesReady.value)
        } finally {
            scope.cancel()
        }
    }

    // 12. WPM updates push to autoScrollController.setDefaultSpeed (via formattingPreferences)
    @Test
    fun `updating autoScrollWpm pushes new default speed to autoScrollController`() = runTest {
        val fakeGlobal = FakeFormattingPreferencesStore(FormattingPreferences(autoScrollWpm = 250))
        val (session, _, _, _, autoScrollController, scope) = makeEager(fakeGlobal = fakeGlobal)
        try {
            session.bindToBook("item1")

            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertEquals(250, (autoScrollController.state.value as AutoScrollState.Running).speed.wpm)

            session.updateFormatting("item1", FormattingPreferences(autoScrollWpm = 400))

            autoScrollController.dispatch(AutoScrollEvent.Stop)
            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertEquals(400, (autoScrollController.state.value as AutoScrollState.Running).speed.wpm)
        } finally {
            autoScrollController.release()
            scope.cancel()
        }
    }

    // 13. init dispatches AutoScrollEvent.Stop to defensively reset singleton state
    @Test
    fun `init dispatches AutoScrollEvent Stop to defensively reset singleton AutoScrollController`() = runTest {
        val dispatcher = UnconfinedTestDispatcher()
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        autoScrollController.dispatch(AutoScrollEvent.Start)
        assertTrue(autoScrollController.state.value is AutoScrollState.Running)

        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        try {
            FormattingSession(
                scope = sessionScope,
                formattingPreferencesStore = FakeFormattingPreferencesStore(),
                bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
                wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
                listeningPreferencesStore = FakeListeningPreferencesStore(),
                autoScrollController = autoScrollController,
                appearanceCoordinator = FakeAppearanceCoordinator(),
            )
            assertTrue(autoScrollController.state.value is AutoScrollState.Idle)
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }

    // 14. onBookClosed dispatches AutoScrollEvent.Stop
    @Test
    fun `onBookClosed dispatches AutoScrollEvent Stop`() = runTest {
        val (session, _, _, _, autoScrollController, scope) = makeEager()
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)

            session.onBookClosed()
            assertTrue(autoScrollController.state.value is AutoScrollState.Idle)
        } finally {
            autoScrollController.release()
            scope.cancel()
        }
    }
}

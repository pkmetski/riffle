package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferencesStoreProvider
import com.riffle.core.models.FormattingScope
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        override suspend fun setCadencePlatformSupported(supported: Boolean) {
            _flow.value = _flow.value.copy(cadencePlatformSupported = supported)
        }
        fun set(p: FormattingPreferences) { _flow.value = p }
    }

    private class FakeBookFormattingPreferencesStore : BookFormattingPreferencesStore {
        // Keyed by (itemId, scope, dimension) so scope and dimension combinations stay independent.
        val saved = mutableMapOf<Triple<String, FormattingScope, ScreenDimensionBucket>, BookFormattingOverrides>()
        private var toReturn: BookFormattingOverrides? = null
        fun willReturn(o: BookFormattingOverrides?) { toReturn = o }
        override suspend fun load(
            itemId: String, scope: FormattingScope, dimension: ScreenDimensionBucket,
        ): BookFormattingOverrides? = saved[Triple(itemId, scope, dimension)] ?: toReturn
        override suspend fun save(
            itemId: String, scope: FormattingScope, dimension: ScreenDimensionBucket, overrides: BookFormattingOverrides,
        ) { saved[Triple(itemId, scope, dimension)] = overrides }
        override suspend fun clear(
            itemId: String, scope: FormattingScope, dimension: ScreenDimensionBucket,
        ) { saved.remove(Triple(itemId, scope, dimension)) }
    }

    private class FakeFormattingPreferencesStoreProvider(
        private val fullBook: FormattingPreferencesStore,
        private val highlights: FormattingPreferencesStore = fullBook,
    ) : FormattingPreferencesStoreProvider {
        override fun store(scope: FormattingScope): FormattingPreferencesStore = when (scope) {
            FormattingScope.FullBook -> fullBook
            FormattingScope.Highlights -> highlights
        }
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
            formattingPreferencesStoreProvider = FakeFormattingPreferencesStoreProvider(fakeGlobal),
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
            assertTrue(bookStore.saved.containsKey(Triple("item1", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)))
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
            assertFalse(bookStore.saved.containsKey(Triple("item1", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)))
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

    // Regression: when the user has explicitly parked Auto-Scroll from the HUD pill,
    // opening and closing a reader panel (formatting/TOC/search/annotations) must NOT
    // silently resume scrolling. The pill-park is sticky.
    @Test
    fun `panel open then close leaves UserPausedPill parked`() = runTest {
        val (session, _, _, _, autoScrollController, scope) = makeEager()
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            session.pauseAutoScrollFromPill()
            val parked = autoScrollController.state.value
            assertTrue(parked is AutoScrollState.Paused)
            assertEquals(
                com.riffle.core.domain.autoscroll.PauseCause.UserPausedPill,
                (parked as AutoScrollState.Paused).cause,
            )

            session.setAutoScrollPaused(
                paused = true,
                cause = com.riffle.core.domain.autoscroll.PauseCause.PanelOpen,
            )
            session.setAutoScrollPaused(
                paused = false,
                cause = com.riffle.core.domain.autoscroll.PauseCause.PanelOpen,
            )

            val after = autoScrollController.state.value
            assertTrue(
                "user-pill park must survive a panel open/close cycle",
                after is AutoScrollState.Paused,
            )
            assertEquals(
                com.riffle.core.domain.autoscroll.PauseCause.UserPausedPill,
                (after as AutoScrollState.Paused).cause,
            )
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
        fakeBook.saved[Triple("book-a", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)] = BookFormattingOverrides(fontSize = 1.5f)
        fakeBook.saved[Triple("book-b", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)] = BookFormattingOverrides(fontSize = 2.0f)
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
                formattingPreferencesStoreProvider = FakeFormattingPreferencesStoreProvider(FakeFormattingPreferencesStore()),
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

    // 15. pauseAutoScrollFromPill lands in Paused with UserPausedPill so the pill stays visible.
    @Test
    fun `pauseAutoScrollFromPill parks state as Paused with UserPausedPill`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            formattingPreferencesStoreProvider = FakeFormattingPreferencesStoreProvider(FakeFormattingPreferencesStore()),
            bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = FakeAppearanceCoordinator(),
        )
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)

            session.pauseAutoScrollFromPill()
            val s = autoScrollController.state.value
            assertTrue(s is AutoScrollState.Paused)
            assertEquals(
                com.riffle.core.domain.autoscroll.PauseCause.UserPausedPill,
                (s as AutoScrollState.Paused).cause,
            )
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }

    // 16. After PILL_AUTO_HIDE_MS elapses in the UserPausedPill state, the session auto-stops so the
    //     pill doesn't linger forever. Reverting the fix (removing the collectLatest+delay) would
    //     leave state at Paused indefinitely and flip this assertion red.
    @Test
    fun `UserPausedPill auto-stops after PILL_AUTO_HIDE_MS`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            formattingPreferencesStoreProvider = FakeFormattingPreferencesStoreProvider(FakeFormattingPreferencesStore()),
            bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = FakeAppearanceCoordinator(),
        )
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            session.pauseAutoScrollFromPill()
            assertTrue(autoScrollController.state.value is AutoScrollState.Paused)

            testScheduler.advanceTimeBy(FormattingSession.PILL_AUTO_HIDE_MS - 1)
            testScheduler.runCurrent()
            assertTrue(
                "pill must still be up just before auto-hide",
                autoScrollController.state.value is AutoScrollState.Paused,
            )

            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()
            assertTrue(
                "session must auto-stop after PILL_AUTO_HIDE_MS",
                autoScrollController.state.value is AutoScrollState.Idle,
            )
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }

    // Scope isolation: binding with FormattingScope.Highlights routes both reads and writes to the
    // highlights global store and to the (itemId, Highlights) per-book slot. Revert either half of
    // the split (the DAO scope column, the FormattingSession's flatMapLatest over _scope, or the
    // provider selection in bindToBook) and this pins that regression.
    @Test
    fun `bindToBook Highlights reads global from Highlights store not FullBook`() = runTest {
        val fullBook = FakeFormattingPreferencesStore(FormattingPreferences(fontSize = 1.0f))
        val highlights = FakeFormattingPreferencesStore(FormattingPreferences(fontSize = 2.0f))
        val provider = FakeFormattingPreferencesStoreProvider(fullBook, highlights)
        val dispatcher = UnconfinedTestDispatcher()
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            formattingPreferencesStoreProvider = provider,
            bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = FakeAppearanceCoordinator(),
        )
        try {
            session.bindToBook("item1", FormattingScope.Highlights)
            assertEquals(
                "Highlights bind must expose the highlights global's fontSize, not FullBook's",
                2.0f,
                session.effectiveFormattingPreferences.value.fontSize,
            )
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }

    @Test
    fun `updateFormatting under Highlights writes to Highlights per-book slot`() = runTest {
        val fullBook = FakeFormattingPreferencesStore()
        val highlights = FakeFormattingPreferencesStore()
        val provider = FakeFormattingPreferencesStoreProvider(fullBook, highlights)
        val bookStore = FakeBookFormattingPreferencesStore()
        val dispatcher = UnconfinedTestDispatcher()
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            formattingPreferencesStoreProvider = provider,
            bookFormattingPreferencesStore = bookStore,
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = FakeAppearanceCoordinator(),
        )
        try {
            session.bindToBook("book-x", FormattingScope.Highlights)
            session.updateFormatting("book-x", FormattingPreferences(fontSize = 1.75f))

            assertTrue(
                "Highlights write must land in the Highlights per-book slot",
                bookStore.saved.containsKey(Triple("book-x", FormattingScope.Highlights, ScreenDimensionBucket.PhonePortrait)),
            )
            assertFalse(
                "Highlights write must NOT leak into the FullBook per-book slot",
                bookStore.saved.containsKey(Triple("book-x", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)),
            )
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }

    // 17. Resuming from the pill before the timeout cancels the auto-hide so a live session isn't
    //     stopped out from under the user.
    @Test
    fun `resuming before auto-hide cancels the timer`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            formattingPreferencesStoreProvider = FakeFormattingPreferencesStoreProvider(FakeFormattingPreferencesStore()),
            bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
            appearanceCoordinator = FakeAppearanceCoordinator(),
        )
        try {
            autoScrollController.dispatch(AutoScrollEvent.Start)
            session.pauseAutoScrollFromPill()
            testScheduler.advanceTimeBy(FormattingSession.PILL_AUTO_HIDE_MS / 2)

            session.resumeAutoScrollIfPaused()
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)

            // Advance well past the timeout — Resumed state must not be auto-stopped.
            testScheduler.advanceTimeBy(FormattingSession.PILL_AUTO_HIDE_MS * 2)
            testScheduler.runCurrent()
            assertTrue(autoScrollController.state.value is AutoScrollState.Running)
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }

    @Test
    fun `bindToBook seeds from global defaults when no prior row exists`() = runTest {
        val globalPrefs = FormattingPreferences(fontSize = 1.5f, margins = 0.8f)
        val fakeBook = FakeBookFormattingPreferencesStore().also { it.willReturn(null) }
        val fakeGlobal = FakeFormattingPreferencesStore(globalPrefs)
        val b = makeEager(fakeGlobal = fakeGlobal, fakeBook = fakeBook)
        try {
            b.session.bindToBook("book1", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)

            val seeded = fakeBook.saved[Triple("book1", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)]
            assertNotNull(seeded)
            assertEquals(1.5f, seeded!!.fontSize)
            assertEquals(0.8f, seeded.margins)
        } finally {
            b.sessionScope.cancel()
        }
    }

    @Test
    fun `bindToBook dimension A does not affect dimension B`() = runTest {
        val fakeBook = FakeBookFormattingPreferencesStore().also { it.willReturn(null) }
        val b = makeEager(fakeBook = fakeBook)
        val dimA = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Compact)
        val dimB = ScreenDimensionBucket.PhonePortrait
        try {
            b.session.bindToBook("book1", FormattingScope.FullBook, dimA)
            b.session.updateFormatting("book1", b.session.formattingPreferences.value.copy(fontSize = 2.0f))
            b.session.bindToBook("book1", FormattingScope.FullBook, dimB)

            val dimARow = fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimA)]
            val dimBRow = fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimB)]
            assertNotNull(dimARow)
            assertNotNull(dimBRow)
            assertEquals(2.0f, dimARow!!.fontSize)
            assertNotEquals(2.0f, dimBRow!!.fontSize ?: 0f)
        } finally {
            b.sessionScope.cancel()
        }
    }

    @Test
    fun `resetToGlobalDefaults clears only the current dimension`() = runTest {
        val dimA = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Compact)
        val dimB = ScreenDimensionBucket.PhonePortrait
        val fakeBook = FakeBookFormattingPreferencesStore().also { it.willReturn(null) }
        fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimA)] = BookFormattingOverrides(fontSize = 1.2f)
        fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimB)] = BookFormattingOverrides(fontSize = 1.8f)

        val b = makeEager(fakeBook = fakeBook)
        try {
            b.session.bindToBook("book1", FormattingScope.FullBook, dimA)
            b.session.resetToGlobalDefaults("book1")

            assertNull(fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimA)])
            assertNotNull(fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimB)])
        } finally {
            b.sessionScope.cancel()
        }
    }

    @Test
    fun `second bindToBook call cancels the first so the later dimension wins`() = runTest {
        val dimA = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Compact)
        val dimB = ScreenDimensionBucket.PhonePortrait
        val fakeBook = FakeBookFormattingPreferencesStore().also { it.willReturn(null) }

        val b = makeEager(fakeBook = fakeBook)
        try {
            // Call bindToBook twice in quick succession — dimB must win.
            b.session.bindToBook("book1", FormattingScope.FullBook, dimA)
            b.session.bindToBook("book1", FormattingScope.FullBook, dimB)

            // activeDimension must reflect the last call's dimension so any subsequent
            // updateFormatting / resetToGlobalDefaults targets dimB, not dimA.
            val seededB = fakeBook.saved[Triple("book1", FormattingScope.FullBook, dimB)]
            assertNotNull("dimB row must be seeded by the winning bindToBook call", seededB)
        } finally {
            b.sessionScope.cancel()
        }
    }
}

package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.TimeProvider
import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

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

    private class FakeTimeProvider(private var time: LocalTime = LocalTime.of(12, 0)) : TimeProvider {
        override fun nowLocalTime(): LocalTime = time
        fun setTime(t: LocalTime) { time = t }
    }

    /**
     * Creates a FormattingSession backed by [UnconfinedTestDispatcher] so coroutines run eagerly.
     * This avoids the need for advanceUntilIdle() which hangs with while(true) delay loops.
     * The returned [AutoScrollController] must have [AutoScrollController.release] called before the
     * test ends when auto-scroll is started, to prevent its 16ms ticker from blocking runTest cleanup.
     */
    private fun makeEager(
        globalPrefs: FormattingPreferences = FormattingPreferences(),
        bookOverrides: BookFormattingOverrides = BookFormattingOverrides(),
        time: LocalTime = LocalTime.of(12, 0),
        fakeGlobal: FakeFormattingPreferencesStore = FakeFormattingPreferencesStore(globalPrefs),
        fakeBook: FakeBookFormattingPreferencesStore = FakeBookFormattingPreferencesStore().also {
            it.willReturn(bookOverrides)
        },
        fakeTime: FakeTimeProvider = FakeTimeProvider(time),
    ): Bundle {
        val dispatcher = UnconfinedTestDispatcher()
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            timeProvider = fakeTime,
            formattingPreferencesStore = fakeGlobal,
            bookFormattingPreferencesStore = fakeBook,
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
        )
        return Bundle(session, fakeGlobal, fakeBook, fakeTime, autoScrollController, sessionScope)
    }

    private data class Bundle(
        val session: FormattingSession,
        val globalStore: FakeFormattingPreferencesStore,
        val bookStore: FakeBookFormattingPreferencesStore,
        val timeProvider: FakeTimeProvider,
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
            // With UnconfinedTestDispatcher, bindToBook's internal coroutine runs eagerly
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
            // The store write is launched in a coroutine; with UnconfinedTestDispatcher it runs eagerly
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

    // 5. auto theme switches at the configured boundary tick.
    // Uses StandardTestDispatcher + advanceTimeBy for virtual-time control.
    // The scope is cancelled in finally BEFORE runTest's advanceUntilIdle runs,
    // so the while(true) schedule loop doesn't prevent cleanup.
    @Test
    fun `auto theme switches at the configured boundary tick`() = runTest {
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(7, 0),
            nightStart = LocalTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        val global = FormattingPreferences(theme = ReaderTheme.Auto, themeSchedule = schedule)
        val fakeTime = FakeTimeProvider(LocalTime.of(20, 59))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            timeProvider = fakeTime,
            formattingPreferencesStore = FakeFormattingPreferencesStore(global),
            bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
        )
        try {
            session.bindToBook("item1")
            // Advance time enough for init coroutines to start and bindToBook to run.
            // Keep advances small to avoid over-advancing the schedule loop.
            advanceTimeBy(500)

            // At 20:59, should be Light (day)
            assertEquals(ReaderTheme.Light, session.effectiveFormattingPreferences.value.theme)

            // Advance fake clock to 21:00 and advance virtual time past the ~60s delay
            fakeTime.setTime(LocalTime.of(21, 0))
            advanceTimeBy(61_000)

            // scheduleTicks emitted → combine re-evaluated → effectiveFormattingPreferences = Dark
            assertEquals(ReaderTheme.Dark, session.effectiveFormattingPreferences.value.theme)
        } finally {
            // Cancel before runTest's advanceUntilIdle — stops the while(true) loop
            autoScrollController.release()
            sessionScope.cancel()
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
            // dispatch() is synchronous — state is updated immediately
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
            // UnconfinedTestDispatcher: bindToBook's coroutine runs synchronously
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

    // 11. onBookClosed cancels theme schedule subscription
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

            // Start auto-scroll — it picks up the current defaultSpeed (250 WPM)
            autoScrollController.dispatch(AutoScrollEvent.Start)
            assertEquals(250, (autoScrollController.state.value as AutoScrollState.Running).speed.wpm)

            // Update WPM — setDefaultSpeed(400) is called eagerly by the collector
            session.updateFormatting("item1", FormattingPreferences(autoScrollWpm = 400))

            // Restart auto-scroll to use the new default speed
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
        // Put the controller into Running state before constructing FormattingSession
        autoScrollController.dispatch(AutoScrollEvent.Start)
        assertTrue(autoScrollController.state.value is AutoScrollState.Running)

        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        try {
            FormattingSession(
                scope = sessionScope,
                timeProvider = FakeTimeProvider(),
                formattingPreferencesStore = FakeFormattingPreferencesStore(),
                bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
                wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
                listeningPreferencesStore = FakeListeningPreferencesStore(),
                autoScrollController = autoScrollController,
            )
            // Construction alone must reset the singleton to Idle
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

    // 15. onBookClosed cancels the theme schedule job (extends test 11)
    @Test
    fun `onBookClosed cancels theme schedule job and clears formattingPreferencesReady`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(7, 0),
            nightStart = LocalTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        val global = FormattingPreferences(theme = ReaderTheme.Auto, themeSchedule = schedule)
        val fakeTime = FakeTimeProvider(LocalTime.of(20, 59))
        val autoScrollController = AutoScrollController.forTest(dispatcher)
        val sessionScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val session = FormattingSession(
            scope = sessionScope,
            timeProvider = fakeTime,
            formattingPreferencesStore = FakeFormattingPreferencesStore(global),
            bookFormattingPreferencesStore = FakeBookFormattingPreferencesStore(),
            wakeLockPreferencesStore = FakeWakeLockPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            autoScrollController = autoScrollController,
        )
        try {
            session.bindToBook("item1")
            advanceTimeBy(500)
            assertTrue(session.formattingPreferencesReady.value)

            session.onBookClosed()

            // formattingPreferencesReady must be cleared
            assertFalse(session.formattingPreferencesReady.value)

            // Theme schedule must be dead: advancing past the next boundary should NOT flip theme
            fakeTime.setTime(LocalTime.of(21, 0))
            advanceTimeBy(61_000)
            // Still Light — the schedule loop was cancelled before the tick could fire
            assertEquals(ReaderTheme.Light, session.effectiveFormattingPreferences.value.theme)
        } finally {
            autoScrollController.release()
            sessionScope.cancel()
        }
    }
}

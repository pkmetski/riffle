package com.riffle.core.data

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import com.riffle.core.domain.TimeProvider
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/**
 * JVM tests for AppearanceCoordinatorImpl. Exercises:
 * - app-chrome resolution from (AppTheme × systemDark)
 * - reader-theme resolution from Auto + ThemeSchedule
 * - the boundary-tick timer that flips Auto live across day/night crossings (ADR 0022)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceCoordinatorImplTest {

    private class FakeAppThemeStore(initial: AppTheme = AppTheme.System) : AppThemeStore {
        private val _flow = MutableStateFlow(initial)
        override val appTheme: Flow<AppTheme> = _flow
        override suspend fun setAppTheme(value: AppTheme) { _flow.value = value }
        fun set(value: AppTheme) { _flow.value = value }
    }

    private class FakeFormattingPreferencesStore(
        initial: FormattingPreferences = FormattingPreferences(),
    ) : FormattingPreferencesStore {
        private val _flow = MutableStateFlow(initial)
        override val preferences: Flow<FormattingPreferences> = _flow
        override suspend fun update(preferences: FormattingPreferences) { _flow.value = preferences }
        fun set(value: FormattingPreferences) { _flow.value = value }
    }

    private class FakeTimeProvider(private var time: LocalTime = LocalTime.of(12, 0)) : TimeProvider {
        override fun nowLocalTime(): LocalTime = time
        fun setTime(t: LocalTime) { time = t }
    }

    @Test
    fun `app chrome resolves AppTheme System against systemDark flag`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val appThemeStore = FakeAppThemeStore(AppTheme.System)
        val coord = AppearanceCoordinatorImpl(
            appThemeStore = appThemeStore,
            formattingPreferencesStore = FakeFormattingPreferencesStore(),
            timeProvider = FakeTimeProvider(),
            scope = scope,
        )
        try {
            assertEquals(ChromeTheme.Light, coord.resolved.value.appChrome)

            coord.setSystemDark(true)
            assertEquals(ChromeTheme.Dark, coord.resolved.value.appChrome)

            appThemeStore.set(AppTheme.Light)
            assertEquals(
                "explicit Light overrides systemDark",
                ChromeTheme.Light,
                coord.resolved.value.appChrome,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reader theme resolves Auto via schedule at construction time`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(7, 0),
            nightStart = LocalTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        val coord = AppearanceCoordinatorImpl(
            appThemeStore = FakeAppThemeStore(),
            formattingPreferencesStore = FakeFormattingPreferencesStore(
                FormattingPreferences(theme = ReaderTheme.Auto, themeSchedule = schedule),
            ),
            timeProvider = FakeTimeProvider(LocalTime.of(22, 0)),
            scope = scope,
        )
        try {
            assertEquals(ConcreteReaderTheme.Dark, coord.resolved.value.readerTheme)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `non-Auto reader theme passes through unchanged`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val coord = AppearanceCoordinatorImpl(
            appThemeStore = FakeAppThemeStore(),
            formattingPreferencesStore = FakeFormattingPreferencesStore(
                FormattingPreferences(theme = ReaderTheme.Sepia),
            ),
            timeProvider = FakeTimeProvider(LocalTime.of(22, 0)),
            scope = scope,
        )
        try {
            assertEquals(ConcreteReaderTheme.Sepia, coord.resolved.value.readerTheme)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `boundary tick flips Auto theme live at day-night crossing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(7, 0),
            nightStart = LocalTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto, themeSchedule = schedule)
        val fakeTime = FakeTimeProvider(LocalTime.of(20, 59))
        val coord = AppearanceCoordinatorImpl(
            appThemeStore = FakeAppThemeStore(),
            formattingPreferencesStore = FakeFormattingPreferencesStore(prefs),
            timeProvider = fakeTime,
            scope = scope,
        )
        try {
            // Let init coroutines launch and arm the boundary loop.
            advanceTimeBy(500)
            assertEquals(ConcreteReaderTheme.Light, coord.resolved.value.readerTheme)

            // Move the fake clock to the boundary and let the ~60s delay elapse.
            fakeTime.setTime(LocalTime.of(21, 0))
            advanceTimeBy(61_000)

            assertEquals(ConcreteReaderTheme.Dark, coord.resolved.value.readerTheme)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `degenerate schedule with equal day-night times resolves to day and never ticks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val degenerate = ThemeSchedule(
            dayStart = LocalTime.of(12, 0),
            nightStart = LocalTime.of(12, 0),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.Dark,
        )
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto, themeSchedule = degenerate)
        val coord = AppearanceCoordinatorImpl(
            appThemeStore = FakeAppThemeStore(),
            formattingPreferencesStore = FakeFormattingPreferencesStore(prefs),
            timeProvider = FakeTimeProvider(LocalTime.of(23, 59)),
            scope = scope,
        )
        try {
            advanceTimeBy(500)
            // Always-day → Sepia regardless of clock.
            assertEquals(ConcreteReaderTheme.Sepia, coord.resolved.value.readerTheme)

            // Advancing virtual time by a full day must not push the loop into a tick storm
            // — collectLatest parks on awaitCancellation when the schedule is degenerate.
            advanceTimeBy(24L * 3600L * 1000L)
            assertEquals(ConcreteReaderTheme.Sepia, coord.resolved.value.readerTheme)
        } finally {
            scope.cancel()
        }
    }
}

package com.riffle.core.data

import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.common.TimeProvider
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Production [AppearanceCoordinator]. Combines [AppThemeStore], [FormattingPreferencesStore] and
 * the live system-dark flag into a single [ResolvedAppearance] stream, with a boundary-tick timer
 * that re-emits at each ThemeSchedule day/night crossing.
 *
 * Scoped to an injected [CoroutineScope] (the application scope from AppModule) so the boundary
 * timer survives Activity recreation and is shared across every consumer.
 */
class AppearanceCoordinatorImpl(
    appThemeStore: AppThemeStore,
    formattingPreferencesStore: FormattingPreferencesStore,
    private val timeProvider: TimeProvider,
    scope: CoroutineScope,
) : AppearanceCoordinator {

    private val systemDark = MutableStateFlow(false)

    // Bumped on each boundary-timer fire so the `resolved` combine recomputes against a fresh
    // `now`. The actual tick value is irrelevant — only that it changes.
    private val scheduleTick = MutableStateFlow(0L)

    override val resolved: StateFlow<ResolvedAppearance> = combine(
        appThemeStore.appTheme,
        formattingPreferencesStore.preferences,
        systemDark,
        scheduleTick,
    ) { appTheme, prefs, sysDark, _ ->
        val now = timeProvider.nowLocalTime()
        val resolvedReader = if (prefs.theme == ReaderTheme.Auto) {
            prefs.themeSchedule.resolve(now)
        } else {
            prefs.theme
        }
        ResolvedAppearance(
            appChrome = if (appTheme.isDark(sysDark)) ChromeTheme.Dark else ChromeTheme.Light,
            readerTheme = ConcreteReaderTheme.fromReaderTheme(resolvedReader),
            isSystemDark = sysDark,
        )
    }.distinctUntilChanged().stateIn(
        scope,
        SharingStarted.Eagerly,
        ResolvedAppearance(
            appChrome = ChromeTheme.Light,
            readerTheme = ConcreteReaderTheme.Light,
            isSystemDark = false,
        ),
    )

    init {
        // Boundary-tick driver: re-arms whenever the schedule changes or Auto toggles on/off.
        // Lifted from FormattingSession.armThemeSchedule — same algorithm, one home (ADR 0022's
        // "Boundary crossings during an open reading session repaint live").
        scope.launch {
            formattingPreferencesStore.preferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    // Degenerate schedule (equal day/night times) collapses to always-day —
                    // no boundary will ever arrive. Park until the schedule changes.
                    if (schedule.dayStart == schedule.nightStart) {
                        awaitCancellation()
                    }
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val delayMs = msUntilOnClockCircle(now, next)
                        delay(delayMs)
                        scheduleTick.value = scheduleTick.value + 1
                    }
                }
        }
    }

    override fun setSystemDark(isDark: Boolean) {
        systemDark.value = isDark
    }

    private fun msUntilOnClockCircle(now: LocalTime, next: LocalTime): Long {
        val nowSec = now.toSecondOfDay().toLong()
        val nextSec = next.toSecondOfDay().toLong()
        return (((nextSec - nowSec + 24 * 3600) % (24 * 3600)) * 1000L).coerceAtLeast(1_000L)
    }
}

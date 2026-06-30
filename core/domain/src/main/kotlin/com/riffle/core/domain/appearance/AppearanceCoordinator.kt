package com.riffle.core.domain.appearance

import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for "what should the UI render right now?" — combines the user's
 * app-chrome choice ([com.riffle.core.domain.AppTheme]), the reader theme + Auto schedule
 * (ADR 0022) carried in [com.riffle.core.domain.FormattingPreferences], and the live system
 * dark-mode flag into one [StateFlow] every surface consumes.
 *
 * Implementations also own the boundary-tick timer that re-emits at each day/night crossing so a
 * book left open across the threshold repaints live without the user touching anything.
 *
 * Callers in Compose feed reactive system-darkness updates via [setSystemDark]; the rest of the
 * inputs (AppTheme, FormattingPreferences) come from injected stores.
 */
interface AppearanceCoordinator {
    /** The current resolved appearance. Always has a value (Eagerly-started). */
    val resolved: StateFlow<ResolvedAppearance>

    /**
     * Feed the current OS dark-mode flag. Compose callers should run this in a `LaunchedEffect`
     * keyed on `isSystemInDarkTheme()` so reactive OS toggles flow through.
     */
    fun setSystemDark(isDark: Boolean)
}

/**
 * Snapshot of every theme decision a Compose surface needs to render. Chrome and reader are
 * decoupled on purpose — the library/home/settings/player track [appChrome] while the reading
 * surface tracks [readerTheme]; both come from the same coordinator so a screen that mixes them
 * (e.g. a reader toolbar over a chrome surface) sees a consistent snapshot.
 *
 * Both [appChrome] and [readerTheme] are guaranteed to be concrete — Auto/System are resolved
 * away inside the coordinator so consumers never have to think about resolution.
 */
data class ResolvedAppearance(
    val appChrome: ChromeTheme,
    val readerTheme: ConcreteReaderTheme,
    val isSystemDark: Boolean,
)

/** Resolved app-chrome theme — Light or Dark, never System. */
enum class ChromeTheme {
    Light,
    Dark;

    val isDark: Boolean get() = this == Dark
}

/**
 * Resolved reader theme — Light/Dark/DarkDim/Sepia, never Auto. The coordinator resolves Auto
 * via the [com.riffle.core.domain.ThemeSchedule] before exposing it; downstream consumers can
 * therefore type out the Auto case (`when (theme)` exhaustively over four values, not five).
 */
enum class ConcreteReaderTheme {
    Light,
    Dark,
    DarkDim,
    Sepia;

    /** Inverse of [fromReaderTheme]. Useful when an API still speaks in [ReaderTheme]. */
    fun toReaderTheme(): ReaderTheme = when (this) {
        Light -> ReaderTheme.Light
        Dark -> ReaderTheme.Dark
        DarkDim -> ReaderTheme.DarkDim
        Sepia -> ReaderTheme.Sepia
    }

    companion object {
        /**
         * Returns the [ConcreteReaderTheme] for a [ReaderTheme] that is already resolved.
         * Throws if [theme] is [ReaderTheme.Auto] — Auto must be resolved before this is called.
         */
        fun fromReaderTheme(theme: ReaderTheme): ConcreteReaderTheme = when (theme) {
            ReaderTheme.Light -> Light
            ReaderTheme.Dark -> Dark
            ReaderTheme.DarkDim -> DarkDim
            ReaderTheme.Sepia -> Sepia
            ReaderTheme.Auto -> error(
                "ConcreteReaderTheme.fromReaderTheme called with Auto — the coordinator must " +
                    "resolve Auto via ThemeSchedule before constructing a ConcreteReaderTheme.",
            )
        }
    }
}

package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The user-selected theme for the app chrome (everything outside the reading surface). Deliberately
 * separate from the reader's content theme ([FormattingPreferences.theme]): this controls the
 * Material color scheme of the library, home, settings and player, while the reader keeps its own
 * Light/Dark/DarkDim/Sepia/Auto reading-comfort setting.
 */
enum class AppTheme {
    Light,
    Dark,

    /** Follow the OS dark-mode setting. */
    System;

    /** Resolves this choice to a concrete light/dark decision given the current system setting. */
    fun isDark(systemInDark: Boolean): Boolean = when (this) {
        Light -> false
        Dark -> true
        System -> systemInDark
    }
}

/** Device-local persistence for the app-chrome theme. Defaults to [AppTheme.System]. */
interface AppThemeStore {
    val appTheme: Flow<AppTheme>
    suspend fun setAppTheme(value: AppTheme)
}

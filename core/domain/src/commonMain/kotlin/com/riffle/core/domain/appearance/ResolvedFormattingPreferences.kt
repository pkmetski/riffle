package com.riffle.core.domain.appearance

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme

/**
 * Replaces an `Auto` reader theme with the concrete theme [AppearanceCoordinator] has already
 * resolved (schedule crossing or app-theme follow, ADR 0026). For any other theme this is
 * identity, so downstream consumers — the Readium mapper, the palette, the chapter rail — keep
 * reading `prefs.theme` and never have to think about `Auto`.
 *
 * Both platforms call this: Android from `FormattingSession`, iOS from the reader screen. iOS
 * used to skip resolution entirely and fall through to light (#1071 §15.1).
 */
fun FormattingPreferences.withResolvedTheme(appearance: ResolvedAppearance): FormattingPreferences =
    if (theme == ReaderTheme.Auto) copy(theme = appearance.readerTheme.toReaderTheme()) else this

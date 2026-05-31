@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import androidx.compose.ui.graphics.toArgb
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

// Single source: ReaderThemePalette.DARK_DIM_TEXT (Compose Color) → Readium Color (ARGB int).
private val DARK_DIM_TEXT_COLOR: Int = DARK_DIM_TEXT.toArgb()

fun FormattingPreferences.toEpubPreferences(
    isLandscape: Boolean = false,
    isFixedLayout: Boolean = false,
): EpubPreferences {
    val isDoublePage = orientation != ReaderOrientation.Vertical && doublePageSpread && isLandscape
    return EpubPreferences(
        fontSize = fontSize.toDouble(),
        theme = when (theme) {
            ReaderTheme.Light -> Theme.LIGHT
            ReaderTheme.Dark, ReaderTheme.DarkDim -> Theme.DARK
            ReaderTheme.Sepia -> Theme.SEPIA
        },
        // DarkDim is "dark with slightly muted body text" — same dark background, dimmer text.
        textColor = if (theme == ReaderTheme.DarkDim) Color(DARK_DIM_TEXT_COLOR) else null,
        // Null-gating: when the user value equals the default, pass null so Readium leaves
        // the corresponding --USER__* CSS variable unset on :root. The typography-override
        // stylesheet (see TypographyOverride.kt) is gated on the variable's presence, so an
        // unset variable means the publisher's typography is preserved on uncustomised books.
        // The moment the user nudges a setting away from default, the variable appears and
        // both Readium's own rules and our targeted override start applying.
        fontFamily = when (fontFamily) {
            ReaderFontFamily.Serif -> null  // Serif is the default; see FormattingPreferences.DEFAULT_FONT_FAMILY
            ReaderFontFamily.SansSerif -> FontFamily("sans-serif")
            ReaderFontFamily.Monospace -> FontFamily("monospace")
            ReaderFontFamily.Literata -> FontFamily("Literata")
            ReaderFontFamily.Merriweather -> FontFamily("Merriweather")
            ReaderFontFamily.OpenDyslexic -> FontFamily("OpenDyslexic")
        },
        textAlign = if (justifyText) TextAlign.JUSTIFY else null,
        // lineHeight only takes effect when publisherStyles is off
        publisherStyles = false,
        lineHeight = lineSpacing.toDouble().takeIf { lineSpacing != FormattingPreferences.DEFAULT_LINE_SPACING },
        pageMargins = margins.toDouble(),
        scroll = orientation == ReaderOrientation.Vertical,
        // Fixed-layout: spread controls two-page side-by-side rendering.
        // Reflowable: column count is set via RS properties in toFragmentConfiguration().
        spread = if (isFixedLayout) (if (isDoublePage) Spread.ALWAYS else Spread.NEVER) else null,
    )
}

fun FormattingPreferences.toFragmentConfiguration(
    isLandscape: Boolean = false,
    isFixedLayout: Boolean = false,
): EpubNavigatorFragment.Configuration {
    // --RS__colCount is injected as an unconditional inline style on <html> at page-load
    // time, bypassing Readium's 60em media-query threshold that --USER__colCount relies on.
    // --RS__colWidth must be "auto" to remove the default 45em minimum which would otherwise
    // prevent two columns from fitting in a phone-width viewport.
    val isDoublePage = !isFixedLayout &&
        orientation != ReaderOrientation.Vertical &&
        doublePageSpread &&
        isLandscape
    return EpubNavigatorFragment.Configuration(
        // Make our bundled font assets reachable to the WebView. Without this, the
        // @font-face src URLs registered in registerBundledFonts() 404 and the WebView
        // falls back to the default serif — Literata/Merriweather/OpenDyslexic would all
        // render identically.
        servedAssets = listOf("fonts/.*"),
        readiumCssRsProperties = if (isDoublePage) {
            RsProperties(
                colCount = ColCount.TWO,
                overrides = mapOf("--RS__colWidth" to "auto"),
            )
        } else {
            RsProperties()
        },
        // Compose owns all window-inset handling (navigationBarsPadding on the reader Box,
        // status-bar consumed at the AndroidView root). Without this flag, Readium's
        // R2EpubPageFragment reads displayCutout.safeInsetTop directly from decorView and
        // adds it as containerView top-padding — bypassing our inset consumption. On devices
        // with a cutout/punch-hole, this surfaces as a status-bar-height band of page
        // background at the top in scroll mode (paginated mode hides it inside Readium's
        // own vertical content padding).
        shouldApplyInsetsPadding = false,
    )
}

// Registers @font-face declarations for the fonts bundled in assets/fonts/ so Readium's
// WebView can actually load them. Kept out of toFragmentConfiguration() because the
// underlying Url.fromDecodedPath() touches android.net.Uri, which is unmocked in JVM
// unit tests.
fun EpubNavigatorFragment.Configuration.registerBundledFonts() {
    addFontFamilyDeclaration(FontFamily("Literata")) {
        addFontFace { addSource("fonts/Literata-Regular.ttf") }
    }
    addFontFamilyDeclaration(FontFamily("Merriweather")) {
        addFontFace { addSource("fonts/Merriweather-Regular.ttf") }
    }
    addFontFamilyDeclaration(FontFamily("OpenDyslexic")) {
        addFontFace { addSource("fonts/OpenDyslexic-Regular.otf") }
    }
}

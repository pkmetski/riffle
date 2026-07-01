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
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

// Single source: ReaderThemePalette.DARK_DIM_TEXT (Compose Color) → Readium Color (ARGB int).
private val DARK_DIM_TEXT_COLOR: Int = DARK_DIM_TEXT.toArgb()

/**
 * The decoration template set Riffle registers with the Readium engine: Readium's defaults (used
 * by persisted highlight + search) plus our own [HighlightTintStyle] and [NoteGlyphStyle]. Extracted
 * so both the fragment configuration and the post-polyfill re-registration script (see
 * [readiumDecorationTemplatesRegisterJs]) build the same set — otherwise the CSS injected by our
 * capability wouldn't match the styles the fragment thinks it registered.
 */
internal fun riffleDecorationTemplates(): HtmlDecorationTemplates =
    HtmlDecorationTemplates.defaultTemplates().apply {
        set(HighlightTintStyle::class, highlightTintTemplate())
        set(NoteGlyphStyle::class, noteGlyphTemplate())
    }

fun FormattingPreferences.toEpubPreferences(
    isLandscape: Boolean = false,
    isFixedLayout: Boolean = false,
): EpubPreferences {
    val isDoublePage = orientation == ReaderOrientation.Horizontal && doublePageSpread && isLandscape
    return EpubPreferences(
        fontSize = fontSize.toDouble(),
        theme = when (theme) {
            ReaderTheme.Light -> Theme.LIGHT
            ReaderTheme.Dark, ReaderTheme.DarkDim -> Theme.DARK
            ReaderTheme.Sepia -> Theme.SEPIA
            // Auto should be resolved to a concrete theme upstream by the reader VM
            // (via FormattingPreferences.withResolvedTheme). If it reaches here we
            // fall back to LIGHT rather than crashing.
            ReaderTheme.Auto -> Theme.LIGHT
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
        // null (not TextAlign.START) when justify is off, so --USER__textAlign stays unset and the
        // publisher's text alignment is preserved — the original Paginated/Scroll contract (see
        // FormattingPreferencesMapperTest.justifyTextFalseMapsToNullTextAlign). Continuous mode sets
        // its own text-align in ContinuousStyleInjector and does not depend on this mapper.
        textAlign = if (justifyText) TextAlign.JUSTIFY else null,
        // lineHeight only takes effect when publisherStyles is off
        publisherStyles = false,
        lineHeight = lineSpacing.toDouble().takeIf { lineSpacing != FormattingPreferences.DEFAULT_LINE_SPACING },
        pageMargins = margins.toDouble(),
        scroll = orientation != ReaderOrientation.Horizontal,
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
        orientation == ReaderOrientation.Horizontal &&
        doublePageSpread &&
        isLandscape
    return EpubNavigatorFragment.Configuration(
        // Make our bundled font assets reachable to the WebView. Without this, the
        // @font-face src URLs registered in registerBundledFonts() 404 and the WebView
        // falls back to the default serif — Literata/Merriweather/OpenDyslexic would all
        // render identically.
        servedAssets = listOf("fonts/.*"),
        // Readium's built-in templates (used by search highlights) plus our shared
        // [HighlightTintStyle] template for annotation + readaloud highlights and the
        // [NoteGlyphStyle] template for the margin note glyph. Opacity comes from the tint's
        // alpha channel, which is baked into [HighlightColor.argb] — one flat value across
        // themes and features; see [riffleDecorationTemplates] for the single source.
        decorationTemplates = riffleDecorationTemplates(),
        readiumCssRsProperties = when {
            isDoublePage -> RsProperties(
                colCount = ColCount.TWO,
                overrides = mapOf("--RS__colWidth" to "auto"),
            )
            // Reflowable, paginated (not scroll), single page: pin to ONE column. Readium 3.0.0's
            // default already resolves to one column on a phone, but stating it explicitly keeps it
            // the default across future engine upgrades. Readium 3.3.0, for example, changed the
            // default so a phone-width viewport rendered TWO columns — and its decoration renderer
            // mispositions the readaloud highlight in a multi-column layout, so the synced highlight
            // silently vanished. (Landscape double-page still uses TWO columns above, by design.)
            !isFixedLayout && orientation == ReaderOrientation.Horizontal ->
                RsProperties(colCount = ColCount.ONE)
            // Scroll mode and fixed-layout: column count doesn't apply.
            else -> RsProperties()
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

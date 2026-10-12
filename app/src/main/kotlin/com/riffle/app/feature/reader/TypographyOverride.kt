package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences

// Re-export shared symbols so callers within :app keep their existing import paths.
internal val TYPOGRAPHY_OVERRIDE_CSS: String
    get() = com.riffle.feature.reader.TYPOGRAPHY_OVERRIDE_CSS

internal fun typographyOverrideCss(): String = com.riffle.feature.reader.typographyOverrideCss()

internal fun typographyOverrideInjectionJs(): String =
    com.riffle.feature.reader.TYPOGRAPHY_OVERRIDE_INSTALL_SCRIPT

internal val TYPOGRAPHY_OVERRIDES: Map<String, com.riffle.feature.reader.TypographyOverride>
    get() = com.riffle.feature.reader.TYPOGRAPHY_OVERRIDES

/**
 * Fields of [FormattingPreferences] that have no targeted typography override, with the
 * reason. The contract test asserts every excluded field has a documented reason so future
 * maintainers don't accidentally add a field to this list as a way of silencing the test.
 */
internal val EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES: Map<String, String> = mapOf(
    "margins" to "Page margins: left/right come from Readium's built-in body padding (scales with " +
        "--USER__pageMargins). Top/bottom in PAGINATED mode come from static padding on our fragment " +
        "container (applied before any WebView paints, so no reflow). In SCROLL mode no container " +
        "padding is applied — container padding would create a permanent white strip because the WebView " +
        "sits below topPx and content scrolls inside it (the gap never closes). We do NOT inject a " +
        ":root override for top/bottom: in paginated mode that injected AFTER first paint, causing the " +
        "'page falls from above' bug.",
    "fontSize" to "Applied via root-em multiplier; a per-element override would flatten the publisher's size hierarchy.",
    "theme" to "Multi-property (background, text colour, link colour, image filters) — handled by Readium's theme stylesheet, not a single override.",
    "orientation" to "Layout/scroll mode, not a CSS property.",
    "doublePageSpread" to "Column-count layout mode, handled by RsProperties at fragment-creation time.",
    "forcePaginatedInLandscape" to "Layout behaviour override (forces Horizontal orientation in landscape); resolved in the mapper before CSS generation, not a CSS property.",
    "showChapterMap" to "UI affordance outside the reader content; no CSS implication.",
    "coloredChapterMap" to "Chapter-map rendering style outside the reader content; no CSS implication.",
    "showReadingProgressLabels" to "UI affordance outside the reader content; no CSS implication.",
    "showCurrentChapterLabel" to "UI affordance outside the reader content; no CSS implication.",
    "showReadingTimeEstimate" to "UI affordance outside the reader content; no CSS implication.",
    "themeSchedule" to "Schedule metadata used to derive the resolved theme at runtime; has no direct CSS implication.",
    "autoReaderThemeMode" to "Auto-theme resolution mode metadata; the resolved concrete theme is applied through Readium's theme stylesheet, not a targeted CSS override.",
    "appThemeReaderThemes" to "Light-app/dark-app Auto-theme mapping metadata; the resolved concrete theme is applied through Readium's theme stylesheet, not a targeted CSS override.",
    "autoScrollWpm" to "Auto-Scroll pace (words-per-minute); a kinetics setting consumed by AutoScrollController, not a CSS property.",
    "showAutoScroll" to "Settings toggle gating the reader top-bar Auto-Scroll icon; UI affordance, not a CSS property.",
    "cadenceWpm" to "Cadence pace (words-per-minute); a timing setting consumed by CadenceController/WpmTicker, not a CSS property.",
    "showCadence" to "Settings toggle gating the reader top-bar Cadence icon; UI affordance, not a CSS property.",
    "cadenceHighlightColor" to "Cadence sentence-highlight colour; applied via the highlight decoration renderer (same palette as annotations/readaloud), not a per-element CSS override.",
    "cadencePlatformSupported" to "Device WebView capability flag (Intl.Segmenter feature-detect); hides the Cadence Settings entry on unsupported WebViews, not a CSS property.",
)

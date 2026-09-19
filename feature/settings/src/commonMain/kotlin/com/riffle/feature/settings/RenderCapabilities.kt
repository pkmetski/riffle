package com.riffle.feature.settings

/**
 * Which reader-settings controls a renderer can honour.
 *
 * The settings sheet is one screen serving every renderer, so each section asks the capabilities
 * whether it belongs on screen at all: a PDF has no reflowable text, so font family, typography,
 * publisher styles, theming, the reading-mode switch, the double-page toggle and the position
 * overlays are all meaningless there and must not render as dead controls.
 *
 * Lives in `feature:settings/commonMain` so the iOS reader-settings surface gates on the same
 * flags rather than a re-derived copy.
 */
data class RenderCapabilities(
    val supportsFontFamily: Boolean,
    val supportsTextTypography: Boolean,
    val supportsPublisherStyles: Boolean,
    val supportsTheme: Boolean,
    val supportsReadingModeSwitch: Boolean,
    val supportsDoublePage: Boolean,
    val supportsPositionOverlays: Boolean,
) {
    companion object {
        val EPUB = RenderCapabilities(
            supportsFontFamily = true,
            supportsTextTypography = true,
            supportsPublisherStyles = true,
            supportsTheme = true,
            supportsReadingModeSwitch = true,
            supportsDoublePage = true,
            supportsPositionOverlays = true,
        )
        val PDF = RenderCapabilities(
            supportsFontFamily = false,
            supportsTextTypography = false,
            supportsPublisherStyles = false,
            supportsTheme = false,
            supportsReadingModeSwitch = false,
            supportsDoublePage = false,
            supportsPositionOverlays = false,
        )
    }
}

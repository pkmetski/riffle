package com.riffle.app.feature.reader.formatting

data class RenderCapabilities(
    val supportsFontFamily: Boolean,
    val supportsTextTypography: Boolean,
    val supportsPublisherStyles: Boolean,
    val supportsTheme: Boolean,
    val supportsReadingModeSwitch: Boolean,
    val supportsDoublePage: Boolean,
    val supportsReadingProgressLabels: Boolean,
) {
    companion object {
        val EPUB = RenderCapabilities(
            supportsFontFamily = true,
            supportsTextTypography = true,
            supportsPublisherStyles = true,
            supportsTheme = true,
            supportsReadingModeSwitch = true,
            supportsDoublePage = true,
            supportsReadingProgressLabels = true,
        )
        val PDF = RenderCapabilities(
            supportsFontFamily = false,
            supportsTextTypography = false,
            supportsPublisherStyles = false,
            supportsTheme = false,
            supportsReadingModeSwitch = false,
            supportsDoublePage = false,
            supportsReadingProgressLabels = false,
        )
    }
}

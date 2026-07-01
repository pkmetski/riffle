package com.riffle.app.feature.reader.formatting

data class RenderCapabilities(
    val supportsFontFamily: Boolean,
    val supportsPublisherStyles: Boolean,
    val supportsReadingModeSwitch: Boolean,
    val supportsDoublePage: Boolean,
) {
    companion object {
        val EPUB = RenderCapabilities(
            supportsFontFamily = true,
            supportsPublisherStyles = true,
            supportsReadingModeSwitch = true,
            supportsDoublePage = true,
        )
        val PDF = RenderCapabilities(
            supportsFontFamily = false,
            supportsPublisherStyles = false,
            supportsReadingModeSwitch = false,
            supportsDoublePage = false,
        )
    }
}

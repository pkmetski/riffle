package com.riffle.app.feature.readersettings.formatting

/**
 * Alias onto the shared capability flags in `feature:settings/commonMain` so the Android reader
 * settings sheet and the iOS settings surface gate their sections on one definition.
 * `RenderCapabilities.EPUB` / `.PDF` resolve through the alias to the shared companion.
 */
typealias RenderCapabilities = com.riffle.feature.settings.RenderCapabilities

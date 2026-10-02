package com.riffle.feature.settings.ui.readersettings

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Loads a [FontFamily] from the platform's font-asset store by [familyPrefix].
 * Android: reads from the APK's `assets/fonts/` directory via AssetManager.
 * iOS: returns null (fonts are bundled differently; see SharedFontLoader in :shared).
 */
@Composable
internal expect fun rememberAssetFontFamily(familyPrefix: String): FontFamily?

package com.riffle.feature.settings.ui.readersettings

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** iOS stub — asset-manager font loading is Android-only; fonts are bundled via :shared on iOS. */
@Composable
internal actual fun rememberAssetFontFamily(familyPrefix: String): FontFamily? = null

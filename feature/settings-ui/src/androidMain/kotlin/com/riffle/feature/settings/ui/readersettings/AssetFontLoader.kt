package com.riffle.feature.settings.ui.readersettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

@Composable
internal actual fun rememberAssetFontFamily(familyPrefix: String): FontFamily? {
    val assetManager = LocalContext.current.assets
    return remember(familyPrefix) {
        runCatching {
            val files = assetManager.list("fonts").orEmpty()
            val match = files.firstOrNull {
                it.startsWith("$familyPrefix-Regular") && (it.endsWith(".ttf") || it.endsWith(".otf"))
            } ?: files.firstOrNull {
                it.startsWith(familyPrefix) && (it.endsWith(".ttf") || it.endsWith(".otf"))
            } ?: return@runCatching null
            FontFamily(Font(path = "fonts/$match", assetManager = assetManager))
        }.getOrNull()
    }
}

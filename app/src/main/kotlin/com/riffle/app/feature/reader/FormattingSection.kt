package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily

/**
 * Text + page typography controls. Reused by the in-reader settings sheet (Formatting tab)
 * and the global Settings → Formatting screen. Stateless: the host supplies [prefs] and
 * persists via [onPrefsChange]. [capabilities] hides rows the current renderer can't apply
 * (e.g. font-family picking on PDF, see [RenderCapabilities.PDF]).
 */
@Composable
fun FormattingSection(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    capabilities: RenderCapabilities = RenderCapabilities.EPUB,
) {
    Column {
        Text("Text", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        if (capabilities.supportsTextTypography) {
            Text("Font size", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "%.0f%%".format(prefs.fontSize * 100),
                onDecrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize - 0.1f).coerceAtLeast(0.5f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize + 0.1f).coerceAtMost(2.5f).round1())) },
                decrementDescription = "Decrease font size",
                incrementDescription = "Increase font size",
            )
            Spacer(Modifier.height(16.dp))
        }

        if (capabilities.supportsFontFamily) {
            Text("Font", style = MaterialTheme.typography.labelMedium)
            val genericFonts = listOf(ReaderFontFamily.Serif, ReaderFontFamily.SansSerif, ReaderFontFamily.Monospace)
            val bundledFonts = listOf(ReaderFontFamily.Literata, ReaderFontFamily.Merriweather, ReaderFontFamily.OpenDyslexic)
            FontChipRow(genericFonts, prefs.fontFamily) { onPrefsChange(prefs.copy(fontFamily = it)) }
            Spacer(Modifier.height(4.dp))
            FontChipRow(bundledFonts, prefs.fontFamily) { onPrefsChange(prefs.copy(fontFamily = it)) }
            Spacer(Modifier.height(12.dp))
        }

        if (capabilities.supportsTextTypography) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Justify text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = prefs.justifyText, onCheckedChange = { onPrefsChange(prefs.copy(justifyText = it)) })
            }
            Spacer(Modifier.height(16.dp))

            Text("Line spacing", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "${lineSpacingWord(prefs.lineSpacing)} · %.1f×".format(prefs.lineSpacing),
                onDecrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing - 0.1f).coerceAtLeast(1.0f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing + 0.1f).coerceAtMost(2.0f).round1())) },
                decrementDescription = "Decrease line spacing",
                incrementDescription = "Increase line spacing",
            )
            Spacer(Modifier.height(20.dp))
        }

        Text("Page", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Text("Margins", style = MaterialTheme.typography.labelMedium)
        StepperRow(
            label = "${marginsWord(prefs.margins)} · %.1f×".format(prefs.margins),
            onDecrement = { onPrefsChange(prefs.copy(margins = (prefs.margins - 0.1f).coerceAtLeast(0.2f).round1())) },
            onIncrement = { onPrefsChange(prefs.copy(margins = (prefs.margins + 0.1f).coerceAtMost(3.0f).round1())) },
            decrementDescription = "Decrease margins",
            incrementDescription = "Increase margins",
        )
    }
}

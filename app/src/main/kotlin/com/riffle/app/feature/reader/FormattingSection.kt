package com.riffle.app.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import java.util.Locale

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
        if (capabilities.supportsTextTypography || capabilities.supportsFontFamily) {
            Text("Text", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
        }

        if (capabilities.supportsTextTypography) {
            val fontSizeRange = 0.5f..2.5f
            val fontSizeStep = 0.1f
            UnifiedSliderRow(
                title = "Font size",
                caption = "%.0f%%".format(Locale.ROOT, prefs.fontSize * 100),
                value = prefs.fontSize,
                onValueChange = { onPrefsChange(prefs.copy(fontSize = it.round1())) },
                valueRange = fontSizeRange,
                steps = 19,
                majorEvery = 0.5f,
                edgeLeft = { Text("A", style = MaterialTheme.typography.labelMedium) },
                edgeRight = { Text("A", style = MaterialTheme.typography.titleLarge) },
                bubbleLabel = ::fontSizeBubble,
                contentDescription = "Font size",
                onDecrement = {
                    onPrefsChange(prefs.copy(fontSize = steppedTypographyValue(prefs.fontSize, -fontSizeStep, fontSizeRange)))
                },
                onIncrement = {
                    onPrefsChange(prefs.copy(fontSize = steppedTypographyValue(prefs.fontSize, fontSizeStep, fontSizeRange)))
                },
            )
            Spacer(Modifier.height(16.dp))
        }

        if (capabilities.supportsFontFamily) {
            Text("Font", style = MaterialTheme.typography.labelMedium)
            val genericFonts = listOf(ReaderFontFamily.Original, ReaderFontFamily.Serif, ReaderFontFamily.SansSerif, ReaderFontFamily.Monospace)
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

            val lineSpacingRange = 1.0f..2.0f
            val lineSpacingStep = 0.1f
            UnifiedSliderRow(
                title = "Line spacing",
                caption = "${lineSpacingWord(prefs.lineSpacing)} · %.1f×".format(Locale.ROOT, prefs.lineSpacing),
                value = prefs.lineSpacing,
                onValueChange = { onPrefsChange(prefs.copy(lineSpacing = it.round1())) },
                valueRange = lineSpacingRange,
                steps = 9,
                majorEvery = 0.2f,
                edgeLeft = { TightLinesIcon() },
                edgeRight = { LooseLinesIcon() },
                bubbleLabel = ::lineSpacingBubble,
                contentDescription = "Line spacing",
                onDecrement = {
                    onPrefsChange(prefs.copy(lineSpacing = steppedTypographyValue(prefs.lineSpacing, -lineSpacingStep, lineSpacingRange)))
                },
                onIncrement = {
                    onPrefsChange(prefs.copy(lineSpacing = steppedTypographyValue(prefs.lineSpacing, lineSpacingStep, lineSpacingRange)))
                },
            )
            Spacer(Modifier.height(20.dp))
        }

        if (capabilities.supportsTextTypography || capabilities.supportsFontFamily) {
            // Only render the "Page" section header when there's a preceding "Text" section
            // to separate from — otherwise it reads as a redundant header for a single control.
            Text("Page", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
        }
        val marginsRange = 0.2f..3.0f
        val marginsStep = 0.2f
        UnifiedSliderRow(
            title = "Margins",
            caption = "${marginsWord(prefs.margins)} · %.1f×".format(Locale.ROOT, prefs.margins),
            value = prefs.margins,
            onValueChange = { onPrefsChange(prefs.copy(margins = it.round1())) },
            valueRange = marginsRange,
            steps = 27,
            majorEvery = 0.5f,
            edgeLeft = { NarrowMarginsIcon() },
            edgeRight = { WideMarginsIcon() },
            bubbleLabel = ::marginsBubble,
            contentDescription = "Margins",
            onDecrement = {
                onPrefsChange(prefs.copy(margins = steppedTypographyValue(prefs.margins, -marginsStep, marginsRange)))
            },
            onIncrement = {
                onPrefsChange(prefs.copy(margins = steppedTypographyValue(prefs.margins, marginsStep, marginsRange)))
            },
        )
    }
}

@Composable
private fun TightLinesIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.10f
        val pad = size.width * 0.15f
        val w = size.width - pad * 2
        listOf(0.35f, 0.50f, 0.65f).forEach { y ->
            drawRect(color = c, topLeft = Offset(pad, size.height * y - stroke / 2), size = Size(w, stroke))
        }
    }
}

@Composable
private fun LooseLinesIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.10f
        val pad = size.width * 0.15f
        val w = size.width - pad * 2
        listOf(0.20f, 0.50f, 0.80f).forEach { y ->
            drawRect(color = c, topLeft = Offset(pad, size.height * y - stroke / 2), size = Size(w, stroke))
        }
    }
}

@Composable
private fun NarrowMarginsIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.08f
        drawRect(color = c, topLeft = Offset(0f, 0f), size = size, style = Stroke(width = stroke))
        val innerPad = size.width * 0.15f
        listOf(0.35f, 0.55f, 0.75f).forEach { y ->
            drawRect(
                color = c,
                topLeft = Offset(innerPad, size.height * y - stroke),
                size = Size(size.width - innerPad * 2, stroke * 1.5f),
            )
        }
    }
}

@Composable
private fun WideMarginsIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.08f
        drawRect(color = c, topLeft = Offset(0f, 0f), size = size, style = Stroke(width = stroke))
        val innerPad = size.width * 0.30f
        listOf(0.40f, 0.60f).forEach { y ->
            drawRect(
                color = c,
                topLeft = Offset(innerPad, size.height * y - stroke),
                size = Size(size.width - innerPad * 2, stroke * 1.5f),
            )
        }
    }
}

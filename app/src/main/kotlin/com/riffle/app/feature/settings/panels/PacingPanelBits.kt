package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.UnifiedSliderRow
import com.riffle.app.feature.reader.wpmBubble
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.autoscroll.AutoScrollSpeed

/**
 * Shared WPM slider — Cadence and Auto-Scroll use the same 80–600 wpm, step 10, default 250 range
 * per issue #403 (both reuse [AutoScrollSpeed]'s constants). Snapping happens inside
 * [AutoScrollSpeed.of] so the caller can't emit a mid-step value. Renders through
 * [UnifiedSliderRow] so it visually matches the typography sliders in the Formatting sheet.
 */
@Composable
internal fun WpmSliderRow(
    label: String,
    helper: String,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        UnifiedSliderRow(
            title = label,
            caption = "$wpm wpm",
            value = wpm.toFloat(),
            onValueChange = { onWpmChange(AutoScrollSpeed.of(it.toInt()).wpm) },
            valueRange = AutoScrollSpeed.MIN_WPM.toFloat()..AutoScrollSpeed.MAX_WPM.toFloat(),
            steps = (AutoScrollSpeed.MAX_WPM - AutoScrollSpeed.MIN_WPM) / AutoScrollSpeed.STEP_WPM - 1,
            majorEvery = 100f,
            edgeLeft = { SlowIcon() },
            edgeRight = { FastIcon() },
            bubbleLabel = ::wpmBubble,
            contentDescription = "$label speed",
        )
        Text(
            text = helper,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SlowIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    // A stubby left-pointing chevron — reads as "slower / rewind".
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.width * 0.14f
        val cx = size.width * 0.35f
        val yMid = size.height / 2f
        val yTop = size.height * 0.22f
        val yBot = size.height * 0.78f
        drawLine(color = c, start = Offset(cx + size.width * 0.30f, yTop), end = Offset(cx, yMid), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color = c, start = Offset(cx, yMid), end = Offset(cx + size.width * 0.30f, yBot), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun FastIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    // Two right-pointing chevrons — reads as "faster / fast-forward".
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.width * 0.14f
        val yTop = size.height * 0.22f
        val yMid = size.height / 2f
        val yBot = size.height * 0.78f
        listOf(size.width * 0.18f, size.width * 0.50f).forEach { cx ->
            drawLine(color = c, start = Offset(cx, yTop), end = Offset(cx + size.width * 0.30f, yMid), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color = c, start = Offset(cx + size.width * 0.30f, yMid), end = Offset(cx, yBot), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/**
 * Colour-swatch picker for Cadence's highlight — mirrors the Readaloud picker to keep the visual
 * pattern identical (issue #403: "Independent from Readaloud. Same palette."). Selected swatch
 * carries a contrast ring + a check glyph so the pick is unambiguous in screenshots.
 */
@Composable
internal fun HighlightColorRow(
    selected: HighlightColor,
    onSelectedChange: (HighlightColor) -> Unit,
) {
    ListItem(
        headlineContent = { Text("Highlight color") },
        supportingContent = { Text("Independent from Readaloud. Same palette.") },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightColor.entries.forEach { color ->
                    val isSelected = selected == color
                    val swatchColor = Color(color.argb.toLong() and 0xFFFFFFFFL)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onSelectedChange(color) }
                            .then(
                                if (isSelected)
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier,
                            )
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .semantics {
                                contentDescription = color.name.lowercase()
                                    .replaceFirstChar { it.uppercase() } +
                                    " highlight" + if (isSelected) ", selected" else ""
                            },
                    ) {
                        if (isSelected) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                }
            }
        },
    )
}

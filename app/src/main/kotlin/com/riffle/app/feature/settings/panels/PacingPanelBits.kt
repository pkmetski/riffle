package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.autoscroll.AutoScrollSpeed

/**
 * Shared WPM slider — Cadence and Auto-Scroll use the same 80–600 wpm, step 10, default 250 range
 * per issue #403 (both reuse [AutoScrollSpeed]'s constants). Snapping happens inside
 * [AutoScrollSpeed.of] so the caller can't emit a mid-step value.
 */
@Composable
internal fun WpmSliderRow(
    label: String,
    helper: String,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
) {
    Text(
        text = "$label — $wpm wpm",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Slider(
        value = wpm.toFloat(),
        onValueChange = { onWpmChange(AutoScrollSpeed.of(it.toInt()).wpm) },
        valueRange = AutoScrollSpeed.MIN_WPM.toFloat()..AutoScrollSpeed.MAX_WPM.toFloat(),
        steps = (AutoScrollSpeed.MAX_WPM - AutoScrollSpeed.MIN_WPM) / AutoScrollSpeed.STEP_WPM - 1,
    )
    Text(
        text = helper,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
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

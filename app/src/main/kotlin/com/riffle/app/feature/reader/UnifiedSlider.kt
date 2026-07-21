@file:OptIn(ExperimentalMaterial3Api::class)

package com.riffle.app.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Advance [current] by [step] (positive or negative), clamp to [range], and round to 1 decimal.
 * Used by the edge-icon tap handlers on the typography sliders so keyboard-analogue nudging
 * lands on the same 0.1× lattice the slider itself snaps to.
 */
internal fun steppedTypographyValue(
    current: Float,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
): Float {
    val next = (current + step).coerceIn(range)
    return (next * 10f).roundToInt() / 10f
}

internal fun fontSizeBubble(v: Float): String = "${(v * 100).roundToInt()}%"
internal fun lineSpacingBubble(v: Float): String = "%.1f×".format(Locale.ROOT, v)
internal fun marginsBubble(v: Float): String = "%.1f×".format(Locale.ROOT, v)
internal fun wpmBubble(v: Float): String = "${v.roundToInt()}"

/**
 * True when [value] is an integer multiple of [majorEvery], within [epsilon].
 * Majors line up on natural round numbers (100 wpm, 1.0×, …) regardless of where the range starts.
 */
internal fun isMajorTick(
    value: Float,
    majorEvery: Float,
    epsilon: Float = 1e-3f,
): Boolean {
    val k = value / majorEvery
    return abs(k - k.roundToInt()) < epsilon
}

@Composable
internal fun UnifiedSliderRow(
    title: String,
    caption: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    majorEvery: Float?,
    edgeLeft: @Composable () -> Unit,
    edgeRight: @Composable () -> Unit,
    bubbleLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    contentDescription: String = title,
    onDecrement: (() -> Unit)? = null,
    onIncrement: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .then(
                        if (onDecrement != null) {
                            Modifier
                                .clickable(onClickLabel = "Decrease $contentDescription") { onDecrement() }
                                .semantics { this.contentDescription = "Decrease $contentDescription" }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) { edgeLeft() }
            Spacer(Modifier.width(8.dp))
            SliderTrack(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                majorEvery = majorEvery,
                bubbleLabel = bubbleLabel,
                contentDescription = contentDescription,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .then(
                        if (onIncrement != null) {
                            Modifier
                                .clickable(onClickLabel = "Increase $contentDescription") { onIncrement() }
                                .semantics { this.contentDescription = "Increase $contentDescription" }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) { edgeRight() }
        }
    }
}

@Composable
private fun SliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    majorEvery: Float?,
    bubbleLabel: (Float) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val min = valueRange.start
    val max = valueRange.endInclusive
    val span = max - min
    val trackBaseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val minorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val fillColor = MaterialTheme.colorScheme.primary
    val step = if (steps <= 0) span else span / (steps + 1)
    val showMinors = steps <= 40
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var interacting by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(56.dp)
            .onSizeChanged { trackWidthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            val cy = size.height / 2f
            val trackHeightPx = with(density) { 6.dp.toPx() }
            val corner = trackHeightPx / 2f
            // Match the M3 Slider's thumb travel: the thumb centre only ranges from
            // [thumbHalf, width - thumbHalf], so the track and ticks must live inside
            // that same inset — otherwise the filled portion drifts away from the thumb.
            val thumbHalfPx = with(density) { 10.dp.toPx() }
            val usableWidth = size.width - thumbHalfPx * 2f
            drawRoundRect(
                color = trackBaseColor,
                topLeft = Offset(thumbHalfPx, cy - trackHeightPx / 2f),
                size = Size(usableWidth, trackHeightPx),
                cornerRadius = CornerRadius(corner, corner),
            )
            val filledFrac = ((value - min) / span).coerceIn(0f, 1f)
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(thumbHalfPx, cy - trackHeightPx / 2f),
                size = Size(usableWidth * filledFrac, trackHeightPx),
                cornerRadius = CornerRadius(corner, corner),
            )
            if (steps > 0) {
                for (i in 0..(steps + 1)) {
                    val v = min + i * step
                    val isMajor = majorEvery != null && isMajorTick(v, majorEvery)
                    val isEndpoint = i == 0 || i == steps + 1
                    if (!showMinors && !isMajor && !isEndpoint) continue
                    val x = thumbHalfPx + usableWidth * ((v - min) / span)
                    val tickH = if (isMajor) with(density) { 12.dp.toPx() } else with(density) { 8.dp.toPx() }
                    val tickW = with(density) { 2.dp.toPx() }
                    val color = if (isMajor) fillColor else minorTickColor
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x - tickW / 2f, cy - tickH / 2f),
                        size = Size(tickW, tickH),
                        cornerRadius = CornerRadius(1f, 1f),
                    )
                }
            }
        }

        if (interacting && trackWidthPx > 0) {
            val frac = ((value - min) / span).coerceIn(0f, 1f)
            val thumbHalfPx = with(density) { 10.dp.toPx() }
            val usable = trackWidthPx - thumbHalfPx * 2f
            val xPx = thumbHalfPx + frac * usable
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer { translationX = xPx - 24f },
                ) { Bubble(bubbleLabel(value)) }
            }
        }

        val sliderColors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
        val interactionSource = remember { MutableInteractionSource() }
        Slider(
            value = value,
            onValueChange = { new ->
                interacting = true
                onValueChange(new)
            },
            onValueChangeFinished = { interacting = false },
            valueRange = valueRange,
            steps = steps,
            colors = sliderColors,
            interactionSource = interactionSource,
            thumb = {
                // Use M3's own Thumb sized down — keeps the layout math consistent with
                // SliderImpl so the thumb sits at the correct fraction of the track.
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = sliderColors,
                    thumbSize = androidx.compose.ui.unit.DpSize(20.dp, 20.dp),
                )
            },
            // Do NOT override `track` with an empty slot — that collapses the M3 Slider's
            // internal width to the thumb, killing drag. The default track fills width and
            // draws transparent because both activeTrackColor and inactiveTrackColor are
            // Color.Transparent in the SliderColors above, so nothing is painted on top of
            // our custom Canvas.
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
        )
    }
}

@Composable
private fun Bubble(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

package com.riffle.app.feature.reader.cbz

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.app.feature.reader.UnifiedSliderRow

private val ANIM_SPEED_RANGE = 0f..600f
// 13 discrete stops: 0, 50, 100, …, 600. Material3 steps = stops - 2 endpoints = 11.
private const val ANIM_SPEED_STEPS = 11
private const val ANIM_SPEED_MAJOR_EVERY = 200f
private const val ANIM_SPEED_STEP_SIZE = 50f

@Composable
internal fun PanelAnimationSpeedSlider(
    speedMs: Int,
    onSpeedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val value = speedMs.toFloat().coerceIn(ANIM_SPEED_RANGE)
    UnifiedSliderRow(
        title = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_animation_speed),
        caption = animSpeedLabel(speedMs),
        value = value,
        onValueChange = { onSpeedChange(it.toInt()) },
        valueRange = ANIM_SPEED_RANGE,
        steps = ANIM_SPEED_STEPS,
        majorEvery = ANIM_SPEED_MAJOR_EVERY,
        edgeLeft = {},
        edgeRight = {},
        bubbleLabel = ::animSpeedLabel,
        modifier = modifier,
        enabled = enabled,
        onDecrement = { onSpeedChange((speedMs - ANIM_SPEED_STEP_SIZE.toInt()).coerceAtLeast(0)) },
        onIncrement = { onSpeedChange((speedMs + ANIM_SPEED_STEP_SIZE.toInt()).coerceAtMost(600)) },
    )
}

private fun animSpeedLabel(speedMs: Int): String = if (speedMs == 0) "Off" else "${speedMs}ms"
private fun animSpeedLabel(value: Float): String = animSpeedLabel(value.toInt())

package com.riffle.feature.settings.ui.panels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_animation_speed
import com.riffle.feature.settings.ui.generated.resources.ui_off
import com.riffle.feature.settings.ui.readersettings.UnifiedSliderRow
import org.jetbrains.compose.resources.stringResource

private val ANIM_SPEED_RANGE = 0f..600f
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
    val offLabel = stringResource(Res.string.ui_off)
    val bubbleLabel = remember(offLabel) { { v: Float -> animSpeedLabel(v.toInt(), offLabel) } }
    val value = speedMs.toFloat().coerceIn(ANIM_SPEED_RANGE)
    UnifiedSliderRow(
        title = stringResource(Res.string.ui_animation_speed),
        caption = animSpeedLabel(speedMs, offLabel),
        value = value,
        onValueChange = { onSpeedChange(it.toInt()) },
        valueRange = ANIM_SPEED_RANGE,
        steps = ANIM_SPEED_STEPS,
        majorEvery = ANIM_SPEED_MAJOR_EVERY,
        edgeLeft = {},
        edgeRight = {},
        bubbleLabel = bubbleLabel,
        modifier = modifier,
        enabled = enabled,
        onDecrement = { onSpeedChange((speedMs - ANIM_SPEED_STEP_SIZE.toInt()).coerceAtLeast(0)) },
        onIncrement = { onSpeedChange((speedMs + ANIM_SPEED_STEP_SIZE.toInt()).coerceAtMost(600)) },
    )
}

private fun animSpeedLabel(speedMs: Int, offLabel: String): String =
    if (speedMs == 0) offLabel else "${speedMs}ms"

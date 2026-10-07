package com.riffle.feature.reader.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_faster
import com.riffle.feature.reader.ui.generated.resources.ui_pause_auto_scroll
import com.riffle.feature.reader.ui.generated.resources.ui_pause_cadence
import com.riffle.feature.reader.ui.generated.resources.ui_resume_auto_scroll
import com.riffle.feature.reader.ui.generated.resources.ui_resume_cadence
import com.riffle.feature.reader.ui.generated.resources.ui_slower
import com.riffle.feature.reader.ui.generated.resources.ui_words_per_minute
import org.jetbrains.compose.resources.stringResource

/** Builds [SpeedHudLabels] for the auto-scroll pill from composeResources. */
@Composable
fun speedHudLabels(): SpeedHudLabels = SpeedHudLabels(
    slower = stringResource(Res.string.ui_slower),
    faster = stringResource(Res.string.ui_faster),
    wordsPerMinute = stringResource(Res.string.ui_words_per_minute),
    pause = stringResource(Res.string.ui_pause_auto_scroll),
    resume = stringResource(Res.string.ui_resume_auto_scroll),
)

/** Builds [SpeedHudLabels] for the Cadence pill from composeResources. */
@Composable
fun cadenceHudLabels(): SpeedHudLabels = SpeedHudLabels(
    slower = stringResource(Res.string.ui_slower),
    faster = stringResource(Res.string.ui_faster),
    wordsPerMinute = stringResource(Res.string.ui_words_per_minute),
    pause = stringResource(Res.string.ui_pause_cadence),
    resume = stringResource(Res.string.ui_resume_cadence),
)

package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.feature.reader.ui.SpeedHudLabels

/**
 * Android's string catalogue for the shared speed HUD pill.
 *
 * The pill lives in `:feature:reader-ui` so the iOS reader renders the same one; only the strings
 * are host-owned, which keeps Android's `res/values{,-bg,-es}` translations working.
 *
 * `pause` / `resume` come from the shared English defaults because they were hardcoded English
 * content descriptions in `:app` before the move — promoting them to translated resources is a
 * separate change, not something this move should quietly do.
 */
@Composable
internal fun androidSpeedHudLabels() = SpeedHudLabels(
    slower = stringResource(R.string.ui_slower),
    faster = stringResource(R.string.ui_faster),
    wordsPerMinute = stringResource(R.string.ui_words_per_minute),
    pause = SpeedHudLabels.English.pause,
    resume = SpeedHudLabels.English.resume,
)

/**
 * The same catalogue for Cadence's pill. Only the play/pause description differs, and it comes
 * from [SpeedHudLabels.EnglishCadence] for the reason above — `:app`'s Cadence pill announced
 * "Pause cadence" / "Resume cadence" as hardcoded English before the move.
 */
@Composable
internal fun androidCadenceHudLabels() = androidSpeedHudLabels().copy(
    pause = SpeedHudLabels.EnglishCadence.pause,
    resume = SpeedHudLabels.EnglishCadence.resume,
)

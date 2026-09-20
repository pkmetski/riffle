package com.riffle.shared.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Forwarder to the one implementation, [com.riffle.feature.source.ui.DefaultCoverPlaceholder].
 *
 * This file used to hold a byte-identical private copy of Android's Canvas. It is kept only as a
 * one-line delegate so `IosAudiobookPlayerScreen` keeps compiling against the old import; new call
 * sites import the `feature:source-ui` one directly, and this can go once that last caller moves.
 */
@Composable
fun DefaultCoverPlaceholder(isAudiobook: Boolean, modifier: Modifier = Modifier) {
    com.riffle.feature.source.ui.DefaultCoverPlaceholder(isAudiobook = isAudiobook, modifier = modifier)
}

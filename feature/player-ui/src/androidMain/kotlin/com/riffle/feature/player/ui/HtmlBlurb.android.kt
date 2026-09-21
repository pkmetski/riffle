package com.riffle.feature.player.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml

/** Android keeps the rich rendering `PlayerSurface` has always used. */
actual fun htmlBlurb(html: String): AnnotatedString = AnnotatedString.fromHtml(html)

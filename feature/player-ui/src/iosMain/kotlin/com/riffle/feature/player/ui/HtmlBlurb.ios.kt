package com.riffle.feature.player.ui

import androidx.compose.ui.text.AnnotatedString

/**
 * Compose Multiplatform 1.10 does not publish `AnnotatedString.fromHtml` for Kotlin/Native, so the
 * blurb renders as plain text on iOS. The *text* is derived by the shared [stripHtmlToText], so the
 * two platforms agree on the wording even though only Android renders the inline emphasis.
 */
actual fun htmlBlurb(html: String): AnnotatedString = AnnotatedString(stripHtmlToText(html))

package com.riffle.feature.library.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml

actual fun htmlBlurb(html: String): AnnotatedString = AnnotatedString.fromHtml(html)

package com.riffle.feature.library.ui

import androidx.compose.ui.text.AnnotatedString

actual fun htmlBlurb(html: String): AnnotatedString = AnnotatedString(stripHtmlTagsToText(html))

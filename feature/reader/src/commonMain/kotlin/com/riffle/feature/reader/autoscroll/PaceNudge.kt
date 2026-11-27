package com.riffle.feature.reader.autoscroll

import com.riffle.core.domain.BookFormattingOverrides

fun nudgedPaceOverride(
    current: BookFormattingOverrides,
    effectiveWpm: Int,
    newWpm: Int?,
    set: (BookFormattingOverrides, Int) -> BookFormattingOverrides,
): BookFormattingOverrides? = newWpm?.let { set(current, it) }

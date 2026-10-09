package com.riffle.core.data

import com.riffle.core.domain.ReaderFontFamily

// Serif was renamed in v2.6 (DB migration 55→56); the old "Serif" enum name maps to Original.
// New Serif rows use this key so legacy devices reading a new database see Original, not Serif.
internal const val SERIF_V2_PERSIST_NAME = "SerifV2"
internal const val LEGACY_SERIF_PERSIST_NAME = "Serif"

internal fun ReaderFontFamily.encodePersistName(): String = when (this) {
    ReaderFontFamily.Serif -> SERIF_V2_PERSIST_NAME
    else -> name
}

internal fun String.decodeFontFamily(): ReaderFontFamily? = when (this) {
    SERIF_V2_PERSIST_NAME -> ReaderFontFamily.Serif
    LEGACY_SERIF_PERSIST_NAME -> ReaderFontFamily.Original
    else -> runCatching { ReaderFontFamily.valueOf(this) }.getOrNull()
}

package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

// Medium-saturation (Tailwind ~400-level) hues: vivid enough to tell apart in the picker and to
// read clearly behind text, without being neon. `argb` carries the full-opacity base color used
// for the settings swatch; the reader applies a theme-dependent alpha (see readerTint()).
// Enum names are intentionally unchanged from the original pastels so persisted preferences (which
// store the name string) keep resolving without a migration.
enum class ReadaloudHighlightColor(val argb: Int) {
    BLUE(0xFF38BDF8.toInt()),
    YELLOW(0xFFFBBF24.toInt()),
    GREEN(0xFF34D399.toInt()),
    PINK(0xFFFB7185.toInt()),
    PURPLE(0xFFA78BFA.toInt()),
}

data class ReadaloudPreferences(
    val highlightColor: ReadaloudHighlightColor = ReadaloudHighlightColor.BLUE,
)

interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}

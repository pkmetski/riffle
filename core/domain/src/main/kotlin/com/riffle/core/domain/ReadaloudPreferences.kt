package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

// Medium-saturation (Tailwind ~400-level) hues at ~50% opacity. `argb` is the final rendered color
// used by both the settings swatch and the reader — no further transformation applied. The alpha
// is pre-baked so text remains legible through the highlight. Enum names are unchanged so persisted
// preferences keep resolving without a migration.
enum class ReadaloudHighlightColor(val argb: Int) {
    BLUE(0x8038BDF8.toInt()),
    YELLOW(0x80FBBF24.toInt()),
    GREEN(0x8034D399.toInt()),
    PINK(0x80FB7185.toInt()),
    PURPLE(0x80A78BFA.toInt()),
}

data class ReadaloudPreferences(
    val highlightColor: ReadaloudHighlightColor = ReadaloudHighlightColor.BLUE,
)

interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}

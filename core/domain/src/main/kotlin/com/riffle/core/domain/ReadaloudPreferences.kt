package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

// Medium-saturation (Tailwind ~400-level) hues at ~30% opacity. `argb` is the final rendered color
// used by both the settings swatch and the reader — no further transformation applied. The alpha
// is pre-baked so text remains legible through the highlight. Enum names are unchanged so persisted
// preferences keep resolving without a migration.
enum class ReadaloudHighlightColor(val argb: Int) {
    BLUE(0x4D38BDF8),
    YELLOW(0x4DFBBF24),
    GREEN(0x4D34D399),
    PINK(0x4DFB7185),
    PURPLE(0x4DA78BFA),
}

data class ReadaloudPreferences(
    val highlightColor: ReadaloudHighlightColor = ReadaloudHighlightColor.BLUE,
)

interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}

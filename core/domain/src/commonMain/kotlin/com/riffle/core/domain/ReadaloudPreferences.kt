package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import com.riffle.core.models.HighlightColor

// Readaloud reuses the shared [HighlightColor] palette so the two reader features (readaloud + user
// annotations) render at exactly the same hue and saturation — one source of truth for base RGB,
// one alpha treatment (`readerTint`) at render time, one picker appearance in Settings. The persisted
// enum name lives in DataStore ("YELLOW"/"GREEN"/"BLUE"/"RED"); legacy "PINK" or "PURPLE" values
// written by earlier builds fall through `PrefCodecs.enum`'s unknown-name path to the default.
data class ReadaloudPreferences(
    val highlightColor: HighlightColor = HighlightColor.BLUE,
)

interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}

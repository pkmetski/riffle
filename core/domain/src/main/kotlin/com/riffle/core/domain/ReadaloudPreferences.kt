package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

enum class ReadaloudHighlightColor(val argb: Int) {
    BLUE(0xFF7DD3FC.toInt()),
    YELLOW(0xFFFDE68A.toInt()),
    GREEN(0xFF86EFAC.toInt()),
    PINK(0xFFFDA4AF.toInt()),
    PURPLE(0xFFC4B5FD.toInt()),
}

data class ReadaloudPreferences(
    val highlightColor: ReadaloudHighlightColor = ReadaloudHighlightColor.BLUE,
)

interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}

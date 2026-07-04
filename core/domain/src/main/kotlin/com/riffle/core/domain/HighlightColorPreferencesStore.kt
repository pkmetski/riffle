package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The app-wide "last-used" highlight colour for user-created annotations.
 *
 * Every time the user picks a colour on a highlight (at creation or on recolour), that colour
 * becomes the default for the next new highlight, so the colour-picker opens with it already
 * selected. Global — not scoped per server or per book. Readaloud has its own separate
 * [ReadaloudPreferencesStore]; the two never share state.
 */
interface HighlightColorPreferencesStore {
    val lastUsedColor: Flow<HighlightColor>
    suspend fun setLastUsedColor(value: HighlightColor)
}

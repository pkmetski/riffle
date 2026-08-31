package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.formattingPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "formatting_preferences")

// Second, fully independent DataStore file backing the annotations reading view's global prefs
// chain. Kept in a distinct file (not key-prefixed in the same file) so the two chains cannot
// accidentally read/write each other's values via a missed prefix.
val Context.formattingPreferencesHighlightsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "formatting_preferences_highlights")

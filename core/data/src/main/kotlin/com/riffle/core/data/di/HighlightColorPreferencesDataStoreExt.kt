package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.highlightColorPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "highlight_color_preferences")

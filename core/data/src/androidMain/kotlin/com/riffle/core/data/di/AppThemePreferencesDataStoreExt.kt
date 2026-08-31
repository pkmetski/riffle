package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.appThemePreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "app_theme_preferences")

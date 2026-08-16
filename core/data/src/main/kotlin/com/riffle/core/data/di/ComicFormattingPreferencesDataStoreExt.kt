package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.comicFormattingPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "comic_formatting_preferences")

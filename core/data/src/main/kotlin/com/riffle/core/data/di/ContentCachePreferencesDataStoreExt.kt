package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.contentCacheSettingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "content_cache_settings")

val Context.contentCacheAccessDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "content_cache_access")

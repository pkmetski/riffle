package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PanelViewPreferencesDataStore

val Context.panelViewPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "panel_view_preferences")

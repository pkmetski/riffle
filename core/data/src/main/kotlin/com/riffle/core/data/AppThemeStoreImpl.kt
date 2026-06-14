package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.AppThemePreferencesDataStore
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AppThemeStoreImpl @Inject constructor(
    @param:AppThemePreferencesDataStore private val dataStore: DataStore<Preferences>,
) : AppThemeStore {

    override val appTheme: Flow<AppTheme> = dataStore.data.map { prefs ->
        // Unknown/missing values fall back to System rather than crashing on a renamed enum.
        prefs[KEY_APP_THEME]?.let { stored ->
            AppTheme.entries.firstOrNull { it.name == stored }
        } ?: AppTheme.System
    }

    override suspend fun setAppTheme(value: AppTheme) {
        dataStore.edit { prefs -> prefs[KEY_APP_THEME] = value.name }
    }

    private companion object {
        val KEY_APP_THEME = stringPreferencesKey("app_theme")
    }
}

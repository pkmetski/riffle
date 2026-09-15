package com.riffle.core.data

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

class IosAppThemeStoreImpl : AppThemeStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val _appTheme = MutableStateFlow(load())

    override val appTheme: Flow<AppTheme> = _appTheme

    override suspend fun setAppTheme(value: AppTheme) {
        defaults.setObject(value.name, forKey = KEY)
        _appTheme.value = value
    }

    private fun load(): AppTheme =
        defaults.stringForKey(KEY)
            ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
            ?: AppTheme.System

    private companion object {
        const val KEY = "app_theme"
    }
}

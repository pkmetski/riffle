package com.riffle.shared.settings

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val appTheme: AppTheme = AppTheme.System,
    val formattingPreferences: FormattingPreferences = FormattingPreferences(),
    val sources: List<Source> = emptyList(),
)

class SettingsViewModel(
    private val appThemeStore: AppThemeStore,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val sourceRepository: SourceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val uiState: StateFlow<SettingsUiState> = combine(
        appThemeStore.appTheme,
        formattingPreferencesStore.preferences,
        sourceRepository.observeAll(),
    ) { appTheme, prefs, sources ->
        SettingsUiState(appTheme = appTheme, formattingPreferences = prefs, sources = sources)
    }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState())

    fun setAppTheme(theme: AppTheme) {
        scope.launch { appThemeStore.setAppTheme(theme) }
    }

    fun removeSource(sourceId: String) {
        scope.launch { sourceRepository.remove(sourceId) }
    }
}

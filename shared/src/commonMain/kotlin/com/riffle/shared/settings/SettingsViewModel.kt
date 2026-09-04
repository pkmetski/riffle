package com.riffle.shared.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
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
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        appThemeStore.appTheme,
        formattingPreferencesStore.preferences,
        sourceRepository.observeAll(),
    ) { appTheme, prefs, sources ->
        SettingsUiState(appTheme = appTheme, formattingPreferences = prefs, sources = sources)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setAppTheme(theme: AppTheme) {
        viewModelScope.launch { appThemeStore.setAppTheme(theme) }
    }

    fun removeSource(sourceId: String) {
        viewModelScope.launch { sourceRepository.remove(sourceId) }
    }
}

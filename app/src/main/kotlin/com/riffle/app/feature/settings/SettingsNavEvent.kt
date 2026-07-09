package com.riffle.app.feature.settings

sealed class SettingsNavEvent {
    data object NavigateToAddSource : SettingsNavEvent()
    data object NavigateToAddLocalFolder : SettingsNavEvent()
    data class NavigateToReadaloudMatches(val sourceId: String) : SettingsNavEvent()
}

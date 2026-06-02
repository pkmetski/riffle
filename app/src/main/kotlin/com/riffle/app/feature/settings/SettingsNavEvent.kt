package com.riffle.app.feature.settings

sealed class SettingsNavEvent {
    data object NavigateToAddServer : SettingsNavEvent()
    data class NavigateToReadaloudMatches(val serverId: String) : SettingsNavEvent()
}

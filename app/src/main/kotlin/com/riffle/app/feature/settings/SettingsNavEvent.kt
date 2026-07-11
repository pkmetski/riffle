package com.riffle.app.feature.settings

sealed class SettingsNavEvent {
    data object NavigateToAddSource : SettingsNavEvent()
}

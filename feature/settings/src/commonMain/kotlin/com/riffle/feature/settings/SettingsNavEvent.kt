package com.riffle.feature.settings

sealed class SettingsNavEvent {
    data object NavigateToAddSource : SettingsNavEvent()
}

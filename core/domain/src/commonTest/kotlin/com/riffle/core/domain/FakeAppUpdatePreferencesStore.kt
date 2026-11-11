package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAppUpdatePreferencesStore : AppUpdatePreferencesStore {

    private val autoUpdateState = MutableStateFlow(true)
    private val betaUpdatesState = MutableStateFlow(false)
    private val seenVersionState = MutableStateFlow(0)

    override val autoUpdateEnabled: Flow<Boolean> = autoUpdateState.asStateFlow()
    override val betaUpdatesEnabled: Flow<Boolean> = betaUpdatesState.asStateFlow()
    override val seenUpdateVersionCode: Flow<Int> = seenVersionState.asStateFlow()

    override suspend fun setAutoUpdateEnabled(value: Boolean) { autoUpdateState.value = value }
    override suspend fun setBetaUpdatesEnabled(value: Boolean) { betaUpdatesState.value = value }
    override suspend fun setSeenUpdateVersionCode(value: Int) { seenVersionState.value = value }
}

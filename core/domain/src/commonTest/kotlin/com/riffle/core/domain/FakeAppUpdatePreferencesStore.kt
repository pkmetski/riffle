package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAppUpdatePreferencesStore : AppUpdatePreferencesStore {

    private val autoUpdateState = MutableStateFlow(true)
    private val ignoredVersionCodeState = MutableStateFlow(0)
    private val betaUpdatesState = MutableStateFlow(false)
    private val ignoredVersionWasPreReleaseState = MutableStateFlow(false)

    override val autoUpdateEnabled: Flow<Boolean> = autoUpdateState.asStateFlow()
    override val ignoredVersionCode: Flow<Int> = ignoredVersionCodeState.asStateFlow()
    override val betaUpdatesEnabled: Flow<Boolean> = betaUpdatesState.asStateFlow()
    override val ignoredVersionWasPreRelease: Flow<Boolean> = ignoredVersionWasPreReleaseState.asStateFlow()

    override suspend fun setAutoUpdateEnabled(value: Boolean) { autoUpdateState.value = value }
    override suspend fun setIgnoredVersionCode(value: Int) { ignoredVersionCodeState.value = value }
    override suspend fun setBetaUpdatesEnabled(value: Boolean) { betaUpdatesState.value = value }
    override suspend fun setIgnoredVersionWasPreRelease(value: Boolean) { ignoredVersionWasPreReleaseState.value = value }
}

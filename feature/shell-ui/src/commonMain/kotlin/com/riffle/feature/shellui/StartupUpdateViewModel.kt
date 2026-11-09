package com.riffle.feature.shellui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.common.Clock
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

fun isDevVersionName(versionName: String): Boolean = versionName.endsWith("-dev")

data class StartupUpdateDialogState(
    val releases: List<ReleaseInfo>,
    val update: AvailableUpdate,
)

class StartupUpdateViewModel(
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdatePreferencesStore: AppUpdatePreferencesStore,
    private val clock: Clock,
    private val isDevBuild: Boolean,
    private val currentVersionCode: Int,
) : ViewModel() {

    private var lastCheckAtMs: Long? = null

    private val _dialogState = MutableStateFlow<StartupUpdateDialogState?>(null)
    val dialogState: StateFlow<StartupUpdateDialogState?> = _dialogState.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState?>(null)
    val downloadState: StateFlow<UpdateDownloadState?> = _downloadState.asStateFlow()

    init {
        checkNow()
    }

    fun checkNow() {
        if (isDevBuild) return
        if (_dialogState.value != null) return
        viewModelScope.launch {
            val autoEnabled = appUpdatePreferencesStore.autoUpdateEnabled.first()
            if (!autoEnabled) return@launch
            val nowMs = clock.nowMs()
            val lastCheck = lastCheckAtMs
            if (lastCheck != null && nowMs >= lastCheck && nowMs - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                return@launch
            }
            lastCheckAtMs = nowMs
            val ignoredCode = appUpdatePreferencesStore.ignoredVersionCode.first()
            val releases = appUpdateRepository.listReleasesSince(currentVersionCode)
                .filter { it.downloadUrl.isNotBlank() }
            if (releases.isEmpty()) return@launch
            val latest = releases.first()
            if (latest.versionCode == ignoredCode) return@launch
            _dialogState.value = StartupUpdateDialogState(
                releases = releases,
                update = AvailableUpdate(
                    versionName = latest.versionName,
                    versionCode = latest.versionCode,
                    downloadUrl = latest.downloadUrl,
                    sizeBytes = latest.sizeBytes,
                    prerelease = latest.prerelease,
                ),
            )
        }
    }

    fun ignoreVersion(versionCode: Int, wasPreRelease: Boolean) {
        viewModelScope.launch {
            appUpdatePreferencesStore.setIgnoredVersionCode(versionCode)
            appUpdatePreferencesStore.setIgnoredVersionWasPreRelease(wasPreRelease)
            _dialogState.value = null
        }
    }

    fun startUpdate(update: AvailableUpdate) {
        viewModelScope.launch {
            appUpdateRepository.downloadAndInstall(update).collect { step ->
                _downloadState.value = step
            }
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 2 * 60 * 60 * 1000L
    }
}

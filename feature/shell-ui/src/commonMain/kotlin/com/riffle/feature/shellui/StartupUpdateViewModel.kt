package com.riffle.feature.shellui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.common.Clock
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

fun isDevVersionName(versionName: String): Boolean = versionName.endsWith("-dev")

data class UpdateState(val update: AvailableUpdate, val latestChangelog: String)

class StartupUpdateViewModel(
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdatePreferencesStore: AppUpdatePreferencesStore,
    private val clock: Clock,
    private val currentVersionCode: Int,
) : ViewModel() {

    private var lastCheckAtMs: Long? = null

    private val _updateState = MutableStateFlow<UpdateState?>(null)
    val updateState: StateFlow<UpdateState?> = _updateState.asStateFlow()

    private val seenVersionCodeState = MutableStateFlow(0)

    val showDot: StateFlow<Boolean> = combine(_updateState, seenVersionCodeState) { state, seen ->
        state != null && state.update.versionCode > seen
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _showSheet = MutableStateFlow(false)
    val showSheet: StateFlow<Boolean> = _showSheet.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState?>(null)
    val downloadState: StateFlow<UpdateDownloadState?> = _downloadState.asStateFlow()

    init {
        viewModelScope.launch {
            seenVersionCodeState.value = appUpdatePreferencesStore.seenUpdateVersionCode.first()
        }
        checkNow()
    }

    fun checkNow() {
        if (_showSheet.value && _downloadState.value != null) return
        viewModelScope.launch {
            val autoEnabled = appUpdatePreferencesStore.autoUpdateEnabled.first()
            if (!autoEnabled) return@launch
            val nowMs = clock.nowMs()
            val lastCheck = lastCheckAtMs
            if (lastCheck != null && nowMs >= lastCheck && nowMs - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                return@launch
            }
            lastCheckAtMs = nowMs
            val releases = appUpdateRepository.listReleasesSince(currentVersionCode)
                .filter { it.downloadUrl.isNotBlank() }
            if (releases.isEmpty()) return@launch
            val latest = releases.first()
            _updateState.value = UpdateState(
                update = AvailableUpdate(
                    versionName = latest.versionName,
                    versionCode = latest.versionCode,
                    downloadUrl = latest.downloadUrl,
                    sizeBytes = latest.sizeBytes,
                    prerelease = latest.prerelease,
                ),
                latestChangelog = latest.changelog,
            )
        }
    }

    fun markUpdateSeen() {
        viewModelScope.launch {
            val code = _updateState.value?.update?.versionCode ?: return@launch
            appUpdatePreferencesStore.setSeenUpdateVersionCode(code)
            seenVersionCodeState.value = code
        }
    }

    fun showUpdateSheet() {
        _showSheet.value = true
    }

    fun dismissSheet() {
        if (_downloadState.value == null) {
            _showSheet.value = false
        }
    }

    fun startUpdate(update: AvailableUpdate) {
        viewModelScope.launch {
            appUpdateRepository.downloadAndInstall(update).collect { step ->
                _downloadState.value = step
            }
        }
    }

    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 2 * 60 * 60 * 1000L
    }
}

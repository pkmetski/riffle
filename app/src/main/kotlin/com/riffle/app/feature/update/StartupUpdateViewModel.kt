package com.riffle.app.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class StartupUpdateDialogState(
    val releases: List<ReleaseInfo>,
    val update: AvailableUpdate,
)

@HiltViewModel
class StartupUpdateViewModel @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdatePreferencesStore: AppUpdatePreferencesStore,
) : ViewModel() {

    // Read directly like SettingsViewModel does — avoids injecting a bare Int into Hilt.
    private val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    private val _dialogState = MutableStateFlow<StartupUpdateDialogState?>(null)
    val dialogState: StateFlow<StartupUpdateDialogState?> = _dialogState.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState?>(null)
    val downloadState: StateFlow<UpdateDownloadState?> = _downloadState.asStateFlow()

    private val checking = AtomicBoolean(false)

    init {
        checkNow()
    }

    fun checkNow() {
        if (_dialogState.value != null) return
        if (!checking.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val autoEnabled = appUpdatePreferencesStore.autoUpdateEnabled.first()
                if (!autoEnabled) return@launch
                val ignoredCode = appUpdatePreferencesStore.ignoredVersionCode.first()
                val releases = appUpdateRepository.listReleasesSince(currentVersionCode)
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
                    ),
                )
            } finally {
                checking.set(false)
            }
        }
    }

    fun ignoreVersion(versionCode: Int) {
        viewModelScope.launch {
            appUpdatePreferencesStore.setIgnoredVersionCode(versionCode)
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
}

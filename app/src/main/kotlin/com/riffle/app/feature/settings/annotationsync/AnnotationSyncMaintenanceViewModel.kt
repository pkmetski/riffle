package com.riffle.app.feature.settings.annotationsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Row data for the per-device list in the Maintenance screen. */
data class MaintenanceDeviceRowUiState(
    val deviceId: String,
    val label: String,
    val secondary: String,
    val isThisDevice: Boolean,
)

/** Top-level state of the Maintenance screen. */
sealed class MaintenanceScreenUiState {
    /** Sync not configured — Maintenance has nothing to operate on. */
    object NotConfigured : MaintenanceScreenUiState()

    /** Configured but no logged-in ABS server has an absUserId yet. */
    object NoNamespace : MaintenanceScreenUiState()

    object Loading : MaintenanceScreenUiState()
    data class Loaded(val devices: List<MaintenanceDeviceRowUiState>) : MaintenanceScreenUiState()
    data class Error(val message: String) : MaintenanceScreenUiState()
}

/** Banner shown after Forget/Compact completes. */
sealed class MaintenanceSnack {
    object None : MaintenanceSnack()
    data class Forgot(val label: String, val files: Int, val sidecarDeleted: Boolean, val failures: Int) : MaintenanceSnack()
    data class Compacted(val rewritten: Int, val removed: Int, val failures: Int) : MaintenanceSnack()
}

/** Form state for the Maintenance screen. */
data class AnnotationSyncMaintenanceUiState(
    val devices: MaintenanceScreenUiState = MaintenanceScreenUiState.Loading,
    val deviceLabel: String = "",
    val showRenameDialog: Boolean = false,
    val pendingForget: MaintenanceDeviceRowUiState? = null,
    val showCompactDialog: Boolean = false,
    val busy: Boolean = false,
    val snack: MaintenanceSnack = MaintenanceSnack.None,
)

@HiltViewModel
class AnnotationSyncMaintenanceViewModel @Inject constructor(
    private val configStore: com.riffle.core.domain.AnnotationSyncConfigStore,
    private val maintenance: AnnotationSyncMaintenance,
    private val deviceIdStore: DeviceIdStore,
    private val deviceLabelStore: DeviceLabelStore,
    private val deviceLabelResolver: DeviceLabelResolver,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AnnotationSyncMaintenanceUiState())
    val state: StateFlow<AnnotationSyncMaintenanceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(deviceLabel = currentDeviceLabel())
            refresh()
        }
    }

    fun onRefresh() {
        viewModelScope.launch { refresh() }
    }

    fun onForgetRequested(row: MaintenanceDeviceRowUiState) {
        if (row.isThisDevice) return
        _state.value = _state.value.copy(pendingForget = row)
    }

    fun onForgetCancelled() {
        _state.value = _state.value.copy(pendingForget = null)
    }

    fun onForgetConfirmed() {
        val row = _state.value.pendingForget ?: return
        _state.value = _state.value.copy(pendingForget = null, busy = true)
        viewModelScope.launch {
            val namespace = resolveNamespace()
            if (namespace == null) {
                _state.value = _state.value.copy(busy = false)
                return@launch
            }
            val result = maintenance.forgetDevice(namespace, row.deviceId)
            _state.value = _state.value.copy(
                busy = false,
                snack = MaintenanceSnack.Forgot(
                    label = row.label,
                    files = result.deletedAnnotationFiles,
                    sidecarDeleted = result.deletedSidecar,
                    failures = result.failures,
                ),
            )
            refresh()
        }
    }

    fun onCompactRequested() {
        _state.value = _state.value.copy(showCompactDialog = true)
    }

    fun onCompactCancelled() {
        _state.value = _state.value.copy(showCompactDialog = false)
    }

    fun onCompactConfirmed() {
        _state.value = _state.value.copy(showCompactDialog = false, busy = true)
        viewModelScope.launch {
            val namespace = resolveNamespace()
            if (namespace == null) {
                _state.value = _state.value.copy(busy = false)
                return@launch
            }
            val result = maintenance.compactTombstones(namespace)
            _state.value = _state.value.copy(
                busy = false,
                snack = MaintenanceSnack.Compacted(
                    rewritten = result.filesRewritten,
                    removed = result.tombstonesRemoved,
                    failures = result.failures,
                ),
            )
            refresh()
        }
    }

    fun onSnackDismissed() {
        _state.value = _state.value.copy(snack = MaintenanceSnack.None)
    }

    fun onRenameDeviceRequested() {
        _state.value = _state.value.copy(showRenameDialog = true)
    }

    fun onRenameDialogDismissed() {
        _state.value = _state.value.copy(showRenameDialog = false)
    }

    fun onRenameDeviceConfirmed(newLabel: String) {
        viewModelScope.launch {
            val trimmed = newLabel.trim().take(MAX_LABEL_CHARS)
            deviceLabelStore.set(trimmed.ifEmpty { null })
            _state.value = _state.value.copy(
                deviceLabel = currentDeviceLabel(),
                showRenameDialog = false,
            )
            refresh()
        }
    }

    private suspend fun refresh() {
        // First gate on whether sync is configured at all — when not, the screen explains and the
        // actions stay disabled. (Reachable from Settings; the row itself nudges the user toward
        // the WebDAV entry, but in case they got here directly we still degrade gracefully.)
        val configured = configStore.observe().first() != null
        if (!configured) {
            _state.value = _state.value.copy(devices = MaintenanceScreenUiState.NotConfigured)
            return
        }
        val namespace = resolveNamespace()
        if (namespace == null) {
            _state.value = _state.value.copy(devices = MaintenanceScreenUiState.NoNamespace)
            return
        }
        _state.value = _state.value.copy(devices = MaintenanceScreenUiState.Loading)
        val rows = maintenance.listDevices(namespace)
        val myDeviceId = deviceIdStore.getOrCreate()
        val ui = rows.map { row ->
            val isMe = row.deviceId == myDeviceId
            val label = row.sidecar?.label?.takeIf { it.isNotBlank() }
                ?: "device-${row.deviceId.take(8)}"
            val parts = mutableListOf<String>()
            parts += "${row.annotationFileCount} files"
            row.sidecar?.lastSeenAt?.takeIf { it.isNotBlank() }?.let { parts += "Last seen $it" }
            row.sidecar?.model?.takeIf { it.isNotBlank() }?.let { parts += it }
            MaintenanceDeviceRowUiState(
                deviceId = row.deviceId,
                label = label,
                secondary = parts.joinToString(" · "),
                isThisDevice = isMe,
            )
        }
        _state.value = _state.value.copy(devices = MaintenanceScreenUiState.Loaded(ui))
    }

    private suspend fun resolveNamespace(): String? {
        // Active server first (most relevant to the user), then any configured ABS server with a
        // known absUserId — Maintenance shows files from whichever account currently owns them.
        serverRepository.getActive()?.absUserId?.takeIf { it.isNotBlank() }?.let { return it }
        val all = serverRepository.observeAll().first()
        return all.firstNotNullOfOrNull { it.absUserId?.takeIf { id -> id.isNotBlank() } }
    }

    private suspend fun currentDeviceLabel(): String =
        deviceLabelResolver.resolveLabel(deviceIdStore.getOrCreate())

    private companion object {
        const val MAX_LABEL_CHARS = 40
    }
}

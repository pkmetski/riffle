package com.riffle.app.feature.settings.annotationsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.data.TestConnectionResult
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of the "Test Connection" action surfaced to the screen. */
sealed class TestConnectionUiState {
    object Idle : TestConnectionUiState()
    object Testing : TestConnectionUiState()
    object Success : TestConnectionUiState()
    object AuthFailed : TestConnectionUiState()
    data class InvalidUrl(val message: String) : TestConnectionUiState()
    data class NetworkError(val message: String) : TestConnectionUiState()
    data class TlsError(val message: String) : TestConnectionUiState()
    data class ServerError(val code: Int) : TestConnectionUiState()
}

/** Row data for the per-device list in the Maintenance section. */
data class DeviceRowUiState(
    val deviceId: String,
    val label: String,
    val secondary: String,
    val isThisDevice: Boolean,
)

/** Snapshot of the Maintenance section's collapsible state. */
sealed class MaintenanceUiState {
    /** No saved config — the section is hidden entirely. */
    object Hidden : MaintenanceUiState()

    /** Config saved but we don't have a usable namespace (no logged-in ABS server). */
    object NoNamespace : MaintenanceUiState()

    object Loading : MaintenanceUiState()
    data class Loaded(val devices: List<DeviceRowUiState>) : MaintenanceUiState()
    data class Error(val message: String) : MaintenanceUiState()
}

/** Banner shown after Forget/Compact completes. */
sealed class MaintenanceSnack {
    object None : MaintenanceSnack()
    data class Forgot(val label: String, val files: Int, val sidecarDeleted: Boolean, val failures: Int) : MaintenanceSnack()
    data class Compacted(val rewritten: Int, val removed: Int, val failures: Int) : MaintenanceSnack()
}

/** Form state for the WebDAV annotation-sync settings screen. */
data class AnnotationSyncSettingsUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: TestConnectionUiState = TestConnectionUiState.Idle,
    val saving: Boolean = false,
    val maintenance: MaintenanceUiState = MaintenanceUiState.Hidden,
    val deviceLabel: String = "",
    val showRenameDialog: Boolean = false,
    val pendingForget: DeviceRowUiState? = null,
    val showCompactDialog: Boolean = false,
    val maintenanceBusy: Boolean = false,
    val snack: MaintenanceSnack = MaintenanceSnack.None,
)

@HiltViewModel
class AnnotationSyncSettingsViewModel @Inject constructor(
    private val configStore: AnnotationSyncConfigStore,
    private val targetFactory: WebDavAnnotationSyncTargetFactory,
    private val maintenance: AnnotationSyncMaintenance,
    private val deviceIdStore: DeviceIdStore,
    private val deviceLabelStore: DeviceLabelStore,
    private val deviceLabelResolver: DeviceLabelResolver,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AnnotationSyncSettingsUiState())
    val state: StateFlow<AnnotationSyncSettingsUiState> = _state.asStateFlow()

    private val _closeRequests = Channel<Unit>(Channel.BUFFERED)
    /** Emits each time the screen should pop back (after a successful Save). */
    val closeRequests: Flow<Unit> = _closeRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            configStore.observe().first()?.let { existing ->
                _state.value = _state.value.copy(
                    baseUrl = existing.baseUrl,
                    username = existing.username,
                    password = existing.password,
                )
                refreshMaintenance()
            }
            _state.value = _state.value.copy(deviceLabel = currentDeviceLabel())
        }
    }

    fun onBaseUrlChanged(value: String) {
        _state.value = _state.value.copy(baseUrl = value, testResult = TestConnectionUiState.Idle)
    }

    fun onUsernameChanged(value: String) {
        _state.value = _state.value.copy(username = value, testResult = TestConnectionUiState.Idle)
    }

    fun onPasswordChanged(value: String) {
        _state.value = _state.value.copy(password = value, testResult = TestConnectionUiState.Idle)
    }

    fun onTestConnection() {
        val s = _state.value
        val config = AnnotationSyncConfig(s.baseUrl, s.username, s.password)
        val target = targetFactory.create(config)
        if (target == null) {
            _state.value = s.copy(
                testResult = TestConnectionUiState.InvalidUrl(
                    "Could not parse URL — it should look like https://example.com/dav/path",
                ),
            )
            return
        }
        _state.value = s.copy(testResult = TestConnectionUiState.Testing)
        viewModelScope.launch {
            val result = target.testConnection()
            _state.value = _state.value.copy(
                testResult = when (result) {
                    TestConnectionResult.Success -> TestConnectionUiState.Success
                    TestConnectionResult.AuthFailed -> TestConnectionUiState.AuthFailed
                    is TestConnectionResult.InvalidUrl -> TestConnectionUiState.InvalidUrl(result.message)
                    is TestConnectionResult.NetworkError -> TestConnectionUiState.NetworkError(result.message)
                    is TestConnectionResult.TlsError -> TestConnectionUiState.TlsError(result.message)
                    is TestConnectionResult.ServerError -> TestConnectionUiState.ServerError(result.code)
                },
            )
        }
    }

    fun onSave() {
        val s = _state.value
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            configStore.save(AnnotationSyncConfig(s.baseUrl, s.username, s.password))
            _state.value = _state.value.copy(saving = false)
            _closeRequests.trySend(Unit)
        }
    }

    fun onClear() {
        viewModelScope.launch {
            configStore.clear()
            _state.value = AnnotationSyncSettingsUiState(deviceLabel = currentDeviceLabel())
        }
    }

    // ---- Maintenance ----

    fun onRefreshMaintenance() {
        viewModelScope.launch { refreshMaintenance() }
    }

    fun onForgetRequested(row: DeviceRowUiState) {
        if (row.isThisDevice) return
        _state.value = _state.value.copy(pendingForget = row)
    }

    fun onForgetCancelled() {
        _state.value = _state.value.copy(pendingForget = null)
    }

    fun onForgetConfirmed() {
        val row = _state.value.pendingForget ?: return
        _state.value = _state.value.copy(pendingForget = null, maintenanceBusy = true)
        viewModelScope.launch {
            val namespace = resolveNamespace()
            if (namespace == null) {
                _state.value = _state.value.copy(maintenanceBusy = false)
                return@launch
            }
            val result = maintenance.forgetDevice(namespace, row.deviceId)
            _state.value = _state.value.copy(
                maintenanceBusy = false,
                snack = MaintenanceSnack.Forgot(
                    label = row.label,
                    files = result.deletedAnnotationFiles,
                    sidecarDeleted = result.deletedSidecar,
                    failures = result.failures,
                ),
            )
            refreshMaintenance()
        }
    }

    fun onCompactRequested() {
        _state.value = _state.value.copy(showCompactDialog = true)
    }

    fun onCompactCancelled() {
        _state.value = _state.value.copy(showCompactDialog = false)
    }

    fun onCompactConfirmed() {
        _state.value = _state.value.copy(showCompactDialog = false, maintenanceBusy = true)
        viewModelScope.launch {
            val namespace = resolveNamespace()
            if (namespace == null) {
                _state.value = _state.value.copy(maintenanceBusy = false)
                return@launch
            }
            val result = maintenance.compactTombstones(namespace)
            _state.value = _state.value.copy(
                maintenanceBusy = false,
                snack = MaintenanceSnack.Compacted(
                    rewritten = result.filesRewritten,
                    removed = result.tombstonesRemoved,
                    failures = result.failures,
                ),
            )
            refreshMaintenance()
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
            refreshMaintenance()
        }
    }

    private suspend fun refreshMaintenance() {
        val namespace = resolveNamespace()
        if (namespace == null) {
            _state.value = _state.value.copy(maintenance = MaintenanceUiState.NoNamespace)
            return
        }
        _state.value = _state.value.copy(maintenance = MaintenanceUiState.Loading)
        val rows = maintenance.listDevices(namespace)
        val myDeviceId = deviceIdStore.getOrCreate()
        val ui = rows.map { row ->
            val isMe = row.deviceId == myDeviceId
            val label = when {
                row.sidecar?.label?.isNotBlank() == true -> row.sidecar!!.label
                else -> "device-${row.deviceId.take(8)}"
            }
            val parts = mutableListOf<String>()
            parts += "${row.annotationFileCount} files"
            row.sidecar?.lastSeenAt?.takeIf { it.isNotBlank() }?.let { parts += "Last seen $it" }
            row.sidecar?.model?.takeIf { it.isNotBlank() }?.let { parts += it }
            DeviceRowUiState(
                deviceId = row.deviceId,
                label = label,
                secondary = parts.joinToString(" · "),
                isThisDevice = isMe,
            )
        }
        _state.value = _state.value.copy(maintenance = MaintenanceUiState.Loaded(ui))
    }

    private suspend fun resolveNamespace(): String? {
        // Active server first (most relevant to the user), then any other configured server with
        // a known absUserId — Maintenance shows files from whichever account currently owns them.
        serverRepository.getActive()?.absUserId?.takeIf { it.isNotBlank() }?.let { return it }
        val all: List<com.riffle.core.domain.Server> = serverRepository.observeAll().first()
        return all.firstNotNullOfOrNull { it.absUserId?.takeIf { id -> id.isNotBlank() } }
    }

    private suspend fun currentDeviceLabel(): String {
        val deviceId = deviceIdStore.getOrCreate()
        return deviceLabelResolver.resolveLabel(deviceId)
    }

    private companion object {
        const val MAX_LABEL_CHARS = 40
    }
}

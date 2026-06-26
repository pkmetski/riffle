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
    val namespace: String,
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
    data class Forgot(val label: String, val files: Int, val legacySidecarDeleted: Boolean, val failures: Int) : MaintenanceSnack()
    data class ForgotUser(val userLabel: String, val files: Int) : MaintenanceSnack()
    data class Renamed(val rewritten: Int, val failures: Int) : MaintenanceSnack()
}

/**
 * A foreign ABS user's group of files on the share — i.e. annotations whose namespace doesn't
 * match the currently-active account's. Expandable to per-device rows so the user can forget
 * individual devices or wipe the whole user with one tap.
 *
 * @property displayLabel Human-readable label — the `username` from any device header
 *     under [namespace], or `null` when no header carried one (legacy files). Renderer falls
 *     back to a truncated id when null.
 */
data class OtherUserGroupUiState(
    val namespace: String,
    val displayLabel: String?,
    val devices: List<MaintenanceDeviceRowUiState>,
    val totalFileCount: Int,
)

/** Form state for the Maintenance screen. */
data class AnnotationSyncMaintenanceUiState(
    val devices: MaintenanceScreenUiState = MaintenanceScreenUiState.Loading,
    val otherUsers: List<OtherUserGroupUiState> = emptyList(),
    val deviceLabel: String = "",
    val showRenameDialog: Boolean = false,
    val pendingForget: MaintenanceDeviceRowUiState? = null,
    val pendingForgetUser: OtherUserGroupUiState? = null,
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
            // Each row carries its own namespace — devices in foreign-user groups belong to a
            // different ABS account, not the active one — so use the row's namespace, not the
            // active-namespace fallback.
            val result = maintenance.forgetDevice(row.namespace, row.deviceId)
            _state.value = _state.value.copy(
                busy = false,
                snack = MaintenanceSnack.Forgot(
                    label = row.label,
                    files = result.deletedAnnotationFiles,
                    legacySidecarDeleted = result.deletedLegacySidecar,
                    failures = result.failures,
                ),
            )
            refresh()
        }
    }

    fun onForgetUserRequested(group: OtherUserGroupUiState) {
        _state.value = _state.value.copy(pendingForgetUser = group)
    }

    fun onForgetUserCancelled() {
        _state.value = _state.value.copy(pendingForgetUser = null)
    }

    fun onForgetUserConfirmed() {
        val group = _state.value.pendingForgetUser ?: return
        _state.value = _state.value.copy(pendingForgetUser = null, busy = true)
        viewModelScope.launch {
            val deleted = maintenance.forgetNamespace(group.namespace)
            _state.value = _state.value.copy(
                busy = false,
                snack = MaintenanceSnack.ForgotUser(
                    userLabel = group.displayLabel ?: group.namespace.take(8) + "…",
                    files = deleted,
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
            val updated = currentDeviceLabel()
            _state.value = _state.value.copy(
                deviceLabel = updated,
                showRenameDialog = false,
            )
            // Rewrite this device's metadata header in every annotation file so peers see the
            // new name immediately, instead of waiting for the next annotation push. Per-file
            // failures are surfaced via the snack so the user knows when peers will see a mix.
            val publishResult = resolveNamespace()?.let { namespace ->
                maintenance.publishHeader(
                    namespace = namespace,
                    deviceId = deviceIdStore.getOrCreate(),
                    label = updated,
                    username = serverRepository.getActive()?.username,
                )
            }
            if (publishResult != null) {
                _state.value = _state.value.copy(
                    snack = MaintenanceSnack.Renamed(
                        rewritten = publishResult.rewrittenFiles,
                        failures = publishResult.failures,
                    ),
                )
            }
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
        val serverRows = maintenance.listDevices(namespace)
        val myDeviceId = deviceIdStore.getOrCreate()
        val myLocalLabel = currentDeviceLabel()
        // Surface a "This device" row even when this install hasn't pushed any annotation file
        // yet — otherwise the Rename pencil only appears after the first annotation, and a fresh
        // install can't set its label from Maintenance.
        val rows = if (serverRows.any { it.deviceId == myDeviceId }) {
            serverRows
        } else {
            serverRows + AnnotationSyncMaintenance.DeviceRow(
                deviceId = myDeviceId,
                annotationFileCount = 0,
                metadata = null,
            )
        }
        val ui = rows.map { row -> row.toUiRow(namespace, myDeviceId, myLocalLabel) }
        // Group every OTHER namespace on the share by user, hydrating each group's devices so
        // foreign-user files surface per-device (with their own Forget buttons) instead of being
        // collapsed into a single bulk row. Label each group with the username from the file
        // headers when present; older files without that field fall back to the namespace id.
        val otherUsers = try {
            maintenance.listNamespaces()
                .filter { it.namespace != namespace }
                .map { summary ->
                    val nestedRows = maintenance.listDevices(summary.namespace)
                    // All files under a single namespace belong to the same ABS account, so any
                    // device's recorded username is good for the whole group — pick the first.
                    val username = nestedRows.firstNotNullOfOrNull { it.metadata?.username }
                    OtherUserGroupUiState(
                        namespace = summary.namespace,
                        displayLabel = username,
                        devices = nestedRows.map { it.toUiRow(summary.namespace, myDeviceId = "", myLocalLabel = "") },
                        totalFileCount = summary.annotationFileCount + summary.sidecarCount,
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }

        _state.value = _state.value.copy(
            devices = MaintenanceScreenUiState.Loaded(ui),
            otherUsers = otherUsers,
        )
    }

    /**
     * Project a hydrated [AnnotationSyncMaintenance.DeviceRow] into the UI row shape. The
     * "this device" treatment only applies when [myDeviceId] matches and the row is under the
     * **active** namespace — foreign-user groups should never carry the chip even if a deviceId
     * happens to collide.
     */
    private fun AnnotationSyncMaintenance.DeviceRow.toUiRow(
        namespace: String,
        myDeviceId: String,
        myLocalLabel: String,
    ): MaintenanceDeviceRowUiState {
        val isMe = deviceId == myDeviceId
        val label = when {
            isMe -> myLocalLabel
            else -> metadata?.label?.takeIf { it.isNotBlank() }
                ?: "device-${deviceId.take(8)}"
        }
        val parts = mutableListOf<String>()
        parts += "$annotationFileCount annotation file" + if (annotationFileCount == 1) "" else "s"
        metadata?.lastSeenAt
            ?.takeIf { it.isNotBlank() }
            ?.let { humanizeLastSeen(it) }
            ?.let { parts += "Last synced $it" }
        return MaintenanceDeviceRowUiState(
            deviceId = deviceId,
            namespace = namespace,
            label = label,
            secondary = parts.joinToString(" · "),
            isThisDevice = isMe,
        )
    }

    /** Best-effort ISO-8601 → "yyyy-MM-dd HH:mm" formatter; returns the raw input on failure. */
    private fun humanizeLastSeen(iso: String): String = try {
        val instant = java.time.Instant.parse(iso)
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        iso
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

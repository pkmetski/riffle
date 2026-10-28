package com.riffle.feature.source.ui.localfiles

import com.riffle.core.data.localfiles.FolderUri
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared state machine for the Add-Local-Files onboarding step, used by both platforms.
 *
 * The caller is responsible for invoking the platform folder picker (SAF on Android,
 * UIDocumentPickerViewController on iOS) and passing the result to [onFolderPicked].
 * This class only manages the post-pick states (installing, success, error).
 */
class AddLocalFilesCommonViewModel(
    private val installer: LocalFilesInstallerInterface,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data object Cancelled : State
        data object Installing : State
        data class Success(val added: Int, val failures: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun onFolderPicked(uri: FolderUri?) {
        if (uri == null) {
            _state.value = State.Cancelled
            return
        }
        _state.value = State.Installing
        scope.launch {
            _state.value = try {
                val report = installer.installFolder(uri)
                State.Success(added = report.added, failures = report.failures)
            } catch (t: Throwable) {
                State.Error(t.message ?: t::class.simpleName ?: "Unknown error")
            }
        }
    }
}

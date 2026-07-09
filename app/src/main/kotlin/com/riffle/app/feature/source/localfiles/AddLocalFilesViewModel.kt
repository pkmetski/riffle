package com.riffle.app.feature.source.localfiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.localfiles.LocalFilesScanner
import com.riffle.core.data.localfiles.LocalFilesSourceInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddLocalFilesViewModel @Inject constructor(
    private val installer: LocalFilesSourceInstaller,
) : ViewModel() {

    sealed interface State {
        /** No folder picked yet — the SAF picker should launch. */
        data object Idle : State
        /** User cancelled the SAF picker. */
        data object Cancelled : State
        /** Folder picked; scanning in progress. */
        data object Installing : State
        data class Success(val report: LocalFilesScanner.ScanReport) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) {
            _state.value = State.Cancelled
            return
        }
        _state.value = State.Installing
        viewModelScope.launch {
            _state.value = try {
                val result = installer.installFolder(uri)
                State.Success(result.scan)
            } catch (t: Throwable) {
                State.Error(t.message ?: t::class.simpleName ?: "Unknown error")
            }
        }
    }
}

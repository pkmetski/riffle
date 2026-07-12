package com.riffle.app.feature.source.gutenberg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.domain.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the zero-config Gutenberg install screen. There is no user input — the ViewModel simply
 * calls [SingletonWebSourceInstaller.install] with [SourceType.GUTENBERG] on demand and reports
 * the state. The screen observes [state] and calls `onDone` when the install completes.
 */
@HiltViewModel
class AddGutenbergViewModel @Inject constructor(
    private val installer: SingletonWebSourceInstaller,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data class Success(val sourceId: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun install() {
        if (_state.value is State.Installing || _state.value is State.Success) return
        _state.value = State.Installing
        viewModelScope.launch {
            _state.value = try {
                State.Success(installer.install(SourceType.GUTENBERG))
            } catch (t: Throwable) {
                State.Error(t.message ?: t::class.simpleName ?: "Unknown error")
            }
        }
    }
}

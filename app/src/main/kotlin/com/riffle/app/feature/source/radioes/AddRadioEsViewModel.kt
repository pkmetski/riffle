package com.riffle.app.feature.source.radioes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.models.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddRadioEsViewModel @Inject constructor(
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
                State.Success(installer.install(SourceType.RADIO_ES))
            } catch (t: Throwable) {
                State.Error(t.message ?: t::class.simpleName ?: "Unknown error")
            }
        }
    }
}

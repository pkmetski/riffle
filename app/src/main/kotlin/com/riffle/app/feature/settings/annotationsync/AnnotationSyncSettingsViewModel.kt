package com.riffle.app.feature.settings.annotationsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.TestConnectionResult
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
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

/** Form state for the WebDAV annotation-sync settings screen (Connection only). */
data class AnnotationSyncSettingsUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: TestConnectionUiState = TestConnectionUiState.Idle,
    val saving: Boolean = false,
)

@HiltViewModel
class AnnotationSyncSettingsViewModel @Inject constructor(
    private val configStore: AnnotationSyncConfigStore,
    private val targetFactory: WebDavAnnotationSyncTargetFactory,
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
            }
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
            _state.value = AnnotationSyncSettingsUiState()
        }
    }
}

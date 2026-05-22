package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    var url by mutableStateOf(BuildConfig.DEV_SERVER_URL)
    var username by mutableStateOf(BuildConfig.DEV_USERNAME)
    var password by mutableStateOf(BuildConfig.DEV_PASSWORD)
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var insecureWarning by mutableStateOf<InsecureConnectionType?>(null)

    private val _navigateBack = Channel<Unit>(Channel.CONFLATED)
    val navigateBack = _navigateBack.receiveAsFlow()

    fun onConnect() {
        error = null
        val serverUrl = ServerUrl.parse(url.trim())
        if (serverUrl == null) {
            error = "Enter a valid URL (e.g. https://abs.example.com)"
            return
        }
        if (serverUrl.value.startsWith("http://")) {
            insecureWarning = InsecureConnectionType.HTTP
            return
        }
        doLogin(serverUrl, insecureAllowed = false)
    }

    fun onInsecureWarningAccepted() {
        insecureWarning = null
        val serverUrl = ServerUrl.parse(url.trim()) ?: return
        doLogin(serverUrl, insecureAllowed = true)
    }

    fun onInsecureWarningDismissed() {
        insecureWarning = null
    }

    private fun doLogin(serverUrl: ServerUrl, insecureAllowed: Boolean) {
        viewModelScope.launch {
            isLoading = true
            when (val result = repository.addServer(serverUrl, username, password, insecureAllowed)) {
                is AddServerResult.Success -> _navigateBack.send(Unit)
                is AddServerResult.WrongCredentials -> error = result.message
                is AddServerResult.NetworkError -> error = "Connection failed: ${result.cause.message}"
                is AddServerResult.InsecureConnection -> insecureWarning = result.type
            }
            isLoading = false
        }
    }
}

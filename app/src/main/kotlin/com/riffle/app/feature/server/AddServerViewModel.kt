package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
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

    private val _navigateToSelectLibraries = Channel<PendingServer>(Channel.CONFLATED)
    val navigateToSelectLibraries = _navigateToSelectLibraries.receiveAsFlow()

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
        doAuthenticate(serverUrl, insecureAllowed = false)
    }

    fun onInsecureWarningAccepted() {
        insecureWarning = null
        val serverUrl = ServerUrl.parse(url.trim()) ?: return
        doAuthenticate(serverUrl, insecureAllowed = true)
    }

    fun onInsecureWarningDismissed() {
        insecureWarning = null
    }

    private fun doAuthenticate(serverUrl: ServerUrl, insecureAllowed: Boolean) {
        viewModelScope.launch {
            isLoading = true
            when (val result = repository.authenticate(serverUrl, username, password, insecureAllowed)) {
                is AuthenticateResult.Success -> _navigateToSelectLibraries.send(result.pending)
                is AuthenticateResult.WrongCredentials -> error = result.message
                is AuthenticateResult.NetworkError -> error = "Connection failed: ${result.cause.message}"
                is AuthenticateResult.LibraryFetchFailed ->
                    error = "Connected, but couldn't load libraries: ${result.cause.message}"
                is AuthenticateResult.InsecureConnection -> insecureWarning = result.type
            }
            isLoading = false
        }
    }
}

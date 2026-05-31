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
import com.riffle.core.domain.ServerType
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

    var serverType by mutableStateOf(ServerType.AUDIOBOOKSHELF)
        private set
    var scheme by mutableStateOf(initialScheme(BuildConfig.DEV_SERVER_URL))
        private set
    var host by mutableStateOf(stripScheme(BuildConfig.DEV_SERVER_URL))
        private set
    val url: String get() = scheme + host
    var username by mutableStateOf(BuildConfig.DEV_USERNAME)
    var password by mutableStateOf(BuildConfig.DEV_PASSWORD)
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var insecureWarning by mutableStateOf<InsecureConnectionType?>(null)

    private val _navigateToSelectLibraries = Channel<PendingServer>(Channel.CONFLATED)
    val navigateToSelectLibraries = _navigateToSelectLibraries.receiveAsFlow()

    fun updateServerType(type: ServerType) {
        if (serverType != type) {
            serverType = type
            error = null
        }
    }

    fun updateScheme(value: String) {
        if (value == "http://" || value == "https://") scheme = value
    }

    fun updateHost(value: String) {
        val lower = value.lowercase()
        when {
            lower.startsWith("https://") -> { scheme = "https://"; host = value.substring(8) }
            lower.startsWith("http://") -> { scheme = "http://"; host = value.substring(7) }
            else -> host = value
        }
    }

    private fun initialScheme(devUrl: String): String =
        if (devUrl.startsWith("http://")) "http://" else "https://"

    private fun stripScheme(devUrl: String): String = when {
        devUrl.startsWith("https://") -> devUrl.substring(8)
        devUrl.startsWith("http://") -> devUrl.substring(7)
        else -> devUrl
    }

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
            when (val result = repository.authenticate(serverUrl, username, password, insecureAllowed, serverType)) {
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

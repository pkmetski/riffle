package com.riffle.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.BOOK_MEDIA_TYPE
import com.riffle.core.models.Library
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AddAbsSourceViewModel(
    private val absApi: AbsApi,
    private val absLibraryApi: AbsLibraryApi,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    var url by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val _sourceAdded = Channel<Unit>(Channel.CONFLATED)
    val sourceAdded: Flow<Unit> = _sourceAdded.receiveAsFlow()

    fun onConnect() {
        viewModelScope.launch {
            error = null
            isLoading = true
            try {
                val normalizedUrl = normalizeAbsUrl(url)
                val parsedUrl = SourceUrl.parse(normalizedUrl)
                if (parsedUrl == null) {
                    error = "Invalid server URL — include http:// or https://"
                    return@launch
                }

                val loginResult = absApi.login(
                    baseUrl = parsedUrl.value,
                    username = username,
                    password = password,
                    insecureAllowed = false,
                )
                val loginUser = when (loginResult) {
                    is NetworkResult.Success -> loginResult.value
                    is NetworkResult.Offline -> { error = "No network connection"; return@launch }
                    else -> { error = "Invalid username or password"; return@launch }
                }

                val librariesResult = absLibraryApi.getLibraries(
                    baseUrl = parsedUrl.value,
                    token = loginUser.token,
                    insecureAllowed = false,
                )
                val libraries = when (librariesResult) {
                    is NetworkResult.Success -> librariesResult.value
                        .filter { it.mediaType == BOOK_MEDIA_TYPE }
                        .map { Library(id = it.id, name = it.name, mediaType = it.mediaType, isUnsupported = false) }
                    else -> { error = "Failed to fetch libraries"; return@launch }
                }
                if (libraries.isEmpty()) {
                    error = "No book libraries found on this server"
                    return@launch
                }

                val pending = PendingSource(
                    url = parsedUrl,
                    username = loginUser.username,
                    userId = loginUser.userId,
                    token = loginUser.token,
                    password = password,
                    insecureConnectionAllowed = false,
                    libraries = libraries,
                    serverType = ServerType.AUDIOBOOKSHELF,
                    sourceType = SourceType.ABS,
                )

                when (val result = sourceRepository.commit(pending, emptySet())) {
                    is CommitSourceResult.Success -> _sourceAdded.send(Unit)
                    is CommitSourceResult.Failure -> error = "Failed to save source: ${result.cause.message}"
                }
            } finally {
                isLoading = false
            }
        }
    }
}

internal fun normalizeAbsUrl(raw: String): String {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://")) trimmed
    else "https://$trimmed"
}

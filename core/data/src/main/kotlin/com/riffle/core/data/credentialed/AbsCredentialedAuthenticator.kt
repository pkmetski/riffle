package com.riffle.core.data.credentialed

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.Library
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.errorAsThrowable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CredentialedAuthenticator] for [SourceType.ABS]. Handles both Audiobookshelf servers (full
 * login + library fetch) and Storyteller Services (token-only login, no browsable libraries per
 * ADR 0026), dispatched on the [ServerType] arg.
 *
 * The Audiobookshelf/Storyteller subtype split lives inside this one authenticator because both
 * currently share `SourceType.ABS`; once #441 splits Storyteller into its own SourceType, the two
 * branches can be extracted into peer authenticators keyed by their own SourceType. Until then
 * this class isolates that mess from the generic `SourceRepositoryImpl`.
 */
@Singleton
class AbsCredentialedAuthenticator @Inject constructor(
    private val absApi: AbsApi,
    private val libraryApi: AbsLibraryApi,
    private val storytellerApi: StorytellerApi,
) : CredentialedAuthenticator {
    override val sourceType: SourceType = SourceType.ABS

    override suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult = when (serverType) {
        ServerType.AUDIOBOOKSHELF -> authenticateAudiobookshelf(url, username, password, insecureAllowed)
        ServerType.STORYTELLER_SERVICE -> authenticateStoryteller(url, username, password, insecureAllowed)
    }

    private suspend fun authenticateAudiobookshelf(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult {
        val loginResult = absApi.login(url.value, username, password, insecureAllowed)
        return when (loginResult) {
            NetworkResult.Auth -> AuthenticateResult.WrongCredentials(WRONG_CREDENTIALS_MESSAGE)
            is NetworkResult.InsecureConnection -> AuthenticateResult.InsecureConnection(loginResult.type)
            is NetworkResult.Success -> {
                val libs = libraryApi.getLibraries(url.value, loginResult.value.token, insecureAllowed)
                if (libs !is NetworkResult.Success) {
                    AuthenticateResult.LibraryFetchFailed(libs.errorAsThrowable())
                } else AuthenticateResult.Success(
                    PendingSource(
                        url = url,
                        username = loginResult.value.username,
                        userId = loginResult.value.userId,
                        token = loginResult.value.token,
                        password = password,
                        insecureConnectionAllowed = insecureAllowed,
                        libraries = libs.value
                            .filter { it.mediaType == "book" }
                            .map {
                                Library(
                                    id = it.id,
                                    name = it.name,
                                    mediaType = it.mediaType,
                                    isUnsupported = false,
                                )
                            },
                        serverType = ServerType.AUDIOBOOKSHELF,
                        sourceType = SourceType.ABS,
                    )
                )
            }
            else -> AuthenticateResult.NetworkError(loginResult.errorAsThrowable())
        }
    }

    private suspend fun authenticateStoryteller(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult = when (val result = storytellerApi.login(url.value, username, password, insecureAllowed)) {
        NetworkResult.Auth -> AuthenticateResult.WrongCredentials(WRONG_CREDENTIALS_MESSAGE)
        is NetworkResult.InsecureConnection -> AuthenticateResult.InsecureConnection(result.type)
        is NetworkResult.Success -> AuthenticateResult.Success(
            PendingSource(
                url = url,
                username = username,
                // Storyteller's auth response doesn't expose a user id; identity is the username + token.
                userId = "",
                token = result.value,
                password = password,
                insecureConnectionAllowed = insecureAllowed,
                // Storyteller contributes no browsable Library (ADR 0026) — it is a Settings-only
                // readaloud backend. The local namespace row that hosts its books as matcher input
                // is created in [CredentialedSourceInstaller.install], not surfaced to the user.
                libraries = emptyList(),
                serverType = ServerType.STORYTELLER_SERVICE,
                sourceType = SourceType.ABS,
            )
        )
        else -> AuthenticateResult.NetworkError(result.errorAsThrowable())
    }

    companion object {
        // Surfaced when the network layer maps 401 to [NetworkResult.Auth] — the unified result type
        // drops the server-provided string, so the user-facing message is owned here.
        private const val WRONG_CREDENTIALS_MESSAGE = "Invalid username or password"
    }
}

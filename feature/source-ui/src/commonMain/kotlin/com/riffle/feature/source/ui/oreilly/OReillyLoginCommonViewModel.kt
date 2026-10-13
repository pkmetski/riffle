package com.riffle.feature.source.ui.oreilly

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.OReillyWebSourceDescriptor
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Library
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Shared O'Reilly login logic used by both Android (`OReillyLoginViewModel`) and iOS
 * (`IosOReillyLoginScreen`).
 *
 * Installs an O'Reilly Source from the `orm-jwt` session cookie harvested by the platform's
 * WebView login screen. There is no username/password step — auth happened in the WebView — so
 * this just commits the token plus the fixed Books/Audiobooks libraries and signals completion.
 *
 * Callers must supply a [CoroutineScope] that outlives the screen (e.g., the ViewModel scope on
 * Android, or a Compose `rememberCoroutineScope()` on iOS).
 */
class OReillyLoginCommonViewModel(
    private val repository: SourceRepository,
    private val scope: CoroutineScope,
) {
    enum class State { SigningIn, Installing, Error }

    private val _state = MutableStateFlow(State.SigningIn)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()

    /**
     * Called by the WebView once the `orm-jwt` cookie appears. [sessionCookie] is the FULL cookie
     * header (`orm-jwt` + session cookies), persisted as the source token so the catalog can
     * replay the complete authenticated session. Idempotent — repeated page loads after login
     * won't re-install (guarded on [State.Installing]).
     */
    fun onTokenHarvested(sessionCookie: String, accountEmail: String? = null) {
        if (_state.value == State.Installing) return
        _state.value = State.Installing
        scope.launch {
            val pending = PendingSource(
                url = SourceUrl.parse(OReillyWebSourceDescriptor.OREILLY_BASE_URL)
                    ?: error("O'Reilly base URL must parse"),
                username = accountEmail.orEmpty(),
                userId = "",
                token = sessionCookie,
                password = "",
                insecureConnectionAllowed = false,
                libraries = OReillyWebSourceDescriptor.defaultLibraries.map {
                    Library(id = it.id, name = it.name, mediaType = it.mediaType, isUnsupported = false)
                },
                sourceType = SourceType.OREILLY,
            )
            _state.value = when (repository.commit(pending, hiddenLibraryIds = emptySet())) {
                is CommitSourceResult.Success -> {
                    _done.send(Unit)
                    State.Installing
                }
                is CommitSourceResult.Failure -> State.Error
            }
        }
    }

    fun retry() {
        if (_state.value == State.Error) _state.value = State.SigningIn
    }
}

package com.riffle.app.feature.source.oreilly

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.OReillyWebSourceDescriptor
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Library
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Installs an O'Reilly Source from the `orm-jwt` session cookie harvested by [OReillyLoginScreen]'s
 * WebView. There is no username/password step — auth happened in the WebView — so this just commits
 * the token plus the fixed Books/Audiobooks libraries and signals completion.
 */
class OReillyLoginViewModel(
    private val repository: SourceRepository,
) : ViewModel() {

    enum class State { SigningIn, Installing, Error }

    var state by mutableStateOf(State.SigningIn)
        private set

    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()

    /**
     * Called by the WebView once the `orm-jwt` cookie appears. [sessionCookie] is the FULL cookie
     * header (orm-jwt + session cookies), persisted as the source token so the catalog can replay
     * the complete authenticated session. Idempotent — repeated page loads after login won't
     * re-install (guarded on [State.Installing]).
     */
    fun onTokenHarvested(sessionCookie: String, accountEmail: String?) {
        if (state == State.Installing) return
        state = State.Installing
        viewModelScope.launch {
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
                // serverType is a legacy compatibility axis O'Reilly has no place on; PendingSource
                // defaults it (the Source/Service taxonomy, ADR 0049, replaces it — no new refs here).
                sourceType = SourceType.OREILLY,
            )
            state = when (repository.commit(pending, hiddenLibraryIds = emptySet())) {
                is CommitSourceResult.Success -> {
                    _done.send(Unit)
                    State.Installing
                }
                is CommitSourceResult.Failure -> State.Error
            }
        }
    }

    fun retry() {
        if (state == State.Error) state = State.SigningIn
    }
}

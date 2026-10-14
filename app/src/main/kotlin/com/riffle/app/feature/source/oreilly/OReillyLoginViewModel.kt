package com.riffle.app.feature.source.oreilly

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.SourceRepository
import com.riffle.feature.source.ui.oreilly.OReillyLoginCommonViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Android ViewModel wrapper around [OReillyLoginCommonViewModel].
 *
 * Bridges the shared login/commit logic to Android's ViewModel lifecycle (viewModelScope) and
 * exposes mutable Compose state for [OReillyLoginScreen].
 */
class OReillyLoginViewModel(
    repository: SourceRepository,
) : ViewModel() {

    enum class State { SigningIn, Installing, Error }

    private val common = OReillyLoginCommonViewModel(repository, viewModelScope)

    val done = common.done

    var state by mutableStateOf(State.SigningIn)
        private set

    init {
        common.state.onEach { commonState ->
            state = when (commonState) {
                OReillyLoginCommonViewModel.State.SigningIn -> State.SigningIn
                OReillyLoginCommonViewModel.State.Installing -> State.Installing
                OReillyLoginCommonViewModel.State.Error -> State.Error
            }
        }.launchIn(viewModelScope)
    }

    fun onTokenHarvested(sessionCookie: String, accountEmail: String?) =
        common.onTokenHarvested(sessionCookie, accountEmail)

    fun retry() = common.retry()
}

package com.riffle.app.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    val servers = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setActive(server: Server) {
        viewModelScope.launch { repository.setActive(server.id) }
    }

    fun remove(server: Server) {
        viewModelScope.launch { repository.remove(server.id) }
    }
}

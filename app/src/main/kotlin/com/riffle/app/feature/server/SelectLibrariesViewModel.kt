package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.Library
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SelectLibrariesViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    private var pending: PendingServer? = null

    var libraries by mutableStateOf<List<Library>>(emptyList())
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val _navigateHome = Channel<Unit>(Channel.CONFLATED)
    val navigateHome = _navigateHome.receiveAsFlow()

    fun bind(pendingServer: PendingServer) {
        if (pending != null) return
        pending = pendingServer
        libraries = pendingServer.libraries
        selectedIds = pendingServer.libraries.map { it.id }.toSet()
    }

    fun toggle(libraryId: String) {
        selectedIds = if (libraryId in selectedIds) selectedIds - libraryId else selectedIds + libraryId
    }

    val canContinue: Boolean get() = selectedIds.isNotEmpty() && !isSubmitting

    fun onContinue() {
        val p = pending ?: return
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            isSubmitting = true
            errorMessage = null
            val hidden = p.libraries.map { it.id }.toSet() - selectedIds
            when (val r = repository.commit(p, hidden)) {
                is CommitServerResult.Success -> _navigateHome.send(Unit)
                is CommitServerResult.Failure -> errorMessage = "Couldn't save server: ${r.cause.message}"
            }
            isSubmitting = false
        }
    }
}

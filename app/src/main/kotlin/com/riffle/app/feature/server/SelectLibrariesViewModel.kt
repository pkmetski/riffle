package com.riffle.app.feature.server

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.R
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.models.Library
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SelectLibrariesViewModel constructor(
    private val context: Context,
    private val repository: SourceRepository,
) : ViewModel() {

    private var pending: PendingSource? = null

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

    fun bind(pendingServer: PendingSource) {
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
                is CommitSourceResult.Success -> _navigateHome.send(Unit)
                is CommitSourceResult.Failure -> errorMessage = context.getString(R.string.error_couldnt_save_source, r.cause.message)
            }
            isSubmitting = false
        }
    }
}

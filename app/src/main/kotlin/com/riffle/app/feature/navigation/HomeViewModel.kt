package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val libraryRepository: LibraryRepository,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
) : ViewModel() {

    sealed class StartDestination {
        data object AddServer : StartDestination()
        data class Library(val libraryId: String, val libraryName: String) : StartDestination()
    }

    suspend fun getStartDestination(): StartDestination {
        val servers = serverRepository.observeAll().first()
        if (servers.isEmpty()) return StartDestination.AddServer

        val activeServer = servers.firstOrNull { it.isActive } ?: servers.first()
        val libraries = libraryRepository.observeLibraries().first()
        if (libraries.isEmpty()) return StartDestination.AddServer

        val hiddenIds = visibilityStore.hiddenLibraryIds(activeServer.id).first()
        val firstVisible = libraries.firstOrNull { it.id !in hiddenIds } ?: libraries.first()
        return StartDestination.Library(firstVisible.id, firstVisible.name)
    }
}

package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

    suspend fun getStartDestination(): StartDestination = withContext(Dispatchers.IO) {
        val servers = serverRepository.observeAll().first()
        if (servers.isEmpty()) return@withContext StartDestination.AddServer

        val activeServer = servers.firstOrNull { it.isActive } ?: servers.first()
        var libraries = libraryRepository.observeLibraries().first()

        if (libraries.isEmpty()) {
            libraryRepository.refreshLibraries()
            libraries = libraryRepository.observeLibraries().first()
        }

        if (libraries.isEmpty()) return@withContext StartDestination.AddServer

        val hiddenIds = visibilityStore.hiddenLibraryIds(activeServer.id).first()
        val firstVisible = libraries.firstOrNull { it.id !in hiddenIds } ?: libraries.first()
        StartDestination.Library(firstVisible.id, firstVisible.name)
    }
}

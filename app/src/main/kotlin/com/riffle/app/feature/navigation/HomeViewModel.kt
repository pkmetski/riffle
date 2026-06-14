package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryRefreshResult
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
    private val lastOpenedLibraryStore: LastOpenedLibraryStore,
) : ViewModel() {

    sealed class StartDestination {
        data object AddServer : StartDestination()
        data object NoLibraries : StartDestination()
        data class Library(val libraryId: String, val libraryName: String) : StartDestination()
    }

    suspend fun getStartDestination(): StartDestination = withContext(Dispatchers.IO) {
        val servers = serverRepository.observeAll().first()
        if (servers.isEmpty()) return@withContext StartDestination.AddServer

        val activeServer = servers.firstOrNull { it.isActive } ?: servers.first()
        var libraries = libraryRepository.observeLibraries().first()

        if (libraries.isEmpty()) {
            val refreshResult = libraryRepository.refreshLibraries()
            libraries = libraryRepository.observeLibraries().first()
            if (libraries.isEmpty()) {
                return@withContext when (refreshResult) {
                    LibraryRefreshResult.Success -> StartDestination.AddServer
                    else -> StartDestination.NoLibraries
                }
            }
        }

        val hiddenIds = visibilityStore.hiddenLibraryIds(activeServer.id).first()
        val visible = libraries.filter { it.id !in hiddenIds }
        // Reopen the library the user last had open on this server, as long as it's still visible;
        // otherwise fall back to the first visible library.
        val lastOpenedId = lastOpenedLibraryStore.lastOpenedLibrary(activeServer.id).first()
        val target = visible.firstOrNull { it.id == lastOpenedId }
            ?: visible.firstOrNull()
            ?: libraries.first()
        StartDestination.Library(target.id, target.name)
    }
}

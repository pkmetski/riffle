package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.usecase.RefreshLibraries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val libraryObserver: LibraryObserver,
    private val refreshLibraries: RefreshLibraries,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val lastOpenedLibraryStore: LastOpenedLibraryStore,
    val dispatchers: DispatcherProvider,
) : ViewModel() {

    sealed class StartDestination {
        data object AddSource : StartDestination()
        data object NoLibraries : StartDestination()
        data class Library(val libraryId: String, val libraryName: String) : StartDestination()
    }

    suspend fun getStartDestination(): StartDestination = withContext(dispatchers.io) {
        val servers = sourceRepository.observeAll().first()
        if (servers.isEmpty()) return@withContext StartDestination.AddSource

        val activeServer = servers.firstOrNull { it.isActive } ?: servers.first()
        var libraries = libraryObserver.observeLibraries().first()

        if (libraries.isEmpty()) {
            val refreshResult = refreshLibraries()
            libraries = libraryObserver.observeLibraries().first()
            if (libraries.isEmpty()) {
                return@withContext when (refreshResult) {
                    LibraryRefreshResult.Success -> StartDestination.AddSource
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

package com.riffle.shared

import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Collection
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.READALOUD_MEDIA_TYPE
import com.riffle.core.models.Series
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val absUrl = SourceUrl.parse("http://abs.local:13378")!!
private val absSource = Source(
    id = "abs1",
    url = absUrl,
    isActive = true,
    insecureConnectionAllowed = true,
    username = "test",
    type = SourceType.ABS,
    serverType = ServerType.AUDIOBOOKSHELF,
)
private val storytellerSource = Source(
    id = "st1",
    url = absUrl,
    isActive = false,
    insecureConnectionAllowed = true,
    username = "test",
    type = SourceType.ABS,
    serverType = ServerType.STORYTELLER_SERVICE,
)

private fun makeLibrary(id: String, mediaType: String = "book") =
    Library(id = id, name = id, mediaType = mediaType, isUnsupported = false)

private fun fakeSourceRepository(sources: List<Source> = listOf(absSource)): SourceRepository {
    val flow = MutableStateFlow(sources)
    return object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flow
        override suspend fun getActive(): Source? = flow.value.firstOrNull { it.isActive }
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            error("not used")
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}

private fun fakeLibraryObserver(libraries: List<Library> = emptyList()): LibraryObserver {
    val flow = MutableStateFlow(libraries)
    return object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flow
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flow
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override fun observeItem(sourceId: String, itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }
}

private fun fakeVisibilityStore(hidden: Set<String> = emptySet()): LibraryVisibilityPreferencesStore =
    object : LibraryVisibilityPreferencesStore {
        private val flow = MutableStateFlow(hidden)
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> = flow
        override suspend fun hideLibrary(sourceId: String, libraryId: String) {}
        override suspend fun showLibrary(sourceId: String, libraryId: String) {}
    }

private fun fakeLastOpenedStore(): LastOpenedLibraryStore = object : LastOpenedLibraryStore {
    override fun lastOpenedLibrary(sourceId: String): Flow<String?> = flowOf(null)
    override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {}
}

class DrawerViewModelTest {

    @Test
    fun allServersFiltersOutStorytellerService() = runTest {
        val vm = DrawerViewModel(
            sourceRepository = fakeSourceRepository(listOf(absSource, storytellerSource)),
            libraryObserver = fakeLibraryObserver(),
            visibilityStore = fakeVisibilityStore(),
            lastOpenedLibraryStore = fakeLastOpenedStore(),
        )
        val servers = vm.allServers.value
        assertEquals(1, servers.size)
        assertEquals(absSource.id, servers.first().id)
    }

    @Test
    fun activeServerIsTheFirstActiveSource() = runTest {
        val vm = DrawerViewModel(
            sourceRepository = fakeSourceRepository(listOf(absSource, storytellerSource)),
            libraryObserver = fakeLibraryObserver(),
            visibilityStore = fakeVisibilityStore(),
            lastOpenedLibraryStore = fakeLastOpenedStore(),
        )
        assertEquals(absSource.id, vm.activeServer.value?.id)
    }

    @Test
    fun activeServerIsNullWhenNoSourceIsActive() = runTest {
        val inactive = absSource.copy(isActive = false)
        val vm = DrawerViewModel(
            sourceRepository = fakeSourceRepository(listOf(inactive)),
            libraryObserver = fakeLibraryObserver(),
            visibilityStore = fakeVisibilityStore(),
            lastOpenedLibraryStore = fakeLastOpenedStore(),
        )
        assertNull(vm.activeServer.value)
    }

    @Test
    fun visibleLibrariesFiltersHiddenIds() = runTest {
        val libA = makeLibrary("A")
        val libB = makeLibrary("B")
        val vm = DrawerViewModel(
            sourceRepository = fakeSourceRepository(),
            libraryObserver = fakeLibraryObserver(listOf(libA, libB)),
            visibilityStore = fakeVisibilityStore(hidden = setOf("A")),
            lastOpenedLibraryStore = fakeLastOpenedStore(),
        )
        val visible = vm.visibleLibraries.value
        assertEquals(listOf("B"), visible.map { it.id })
    }

    @Test
    fun visibleLibrariesFiltersReadaloudLibraries() = runTest {
        val regular = makeLibrary("regular")
        val readaloud = makeLibrary("ra", mediaType = READALOUD_MEDIA_TYPE)
        val vm = DrawerViewModel(
            sourceRepository = fakeSourceRepository(),
            libraryObserver = fakeLibraryObserver(listOf(regular, readaloud)),
            visibilityStore = fakeVisibilityStore(),
            lastOpenedLibraryStore = fakeLastOpenedStore(),
        )
        val visible = vm.visibleLibraries.value
        assertEquals(listOf("regular"), visible.map { it.id })
    }
}

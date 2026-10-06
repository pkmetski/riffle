package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.ui.RiffleScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the Riffle hub's item selection callbacks (#1071 §10, #1144).
 *
 * Tapping a book on the hub must fire [RiffleScreen.onItemSelected] and tapping an annotated book
 * on the Annotations tab must fire [RiffleScreen.onAnnotatedBookClick]. Navigation to the detail
 * screen is the caller's responsibility; [RiffleScreen] is now callback-based.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RiffleScreenReadRouteTest {

    private val hubItem = LibraryItem(
        id = "item1",
        libraryId = "lib1",
        title = "Hub Book",
        author = "Hub Author",
        coverUrl = null,
        readingProgress = 0.3f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        sourceId = "source1",
    )

    private val annotatedBook = AnnotatedBook(
        sourceId = "source1",
        itemId = "item1",
        title = "Annotated Title",
        author = "Hub Author",
        coverUrl = null,
        highlightCount = 2,
        latestUpdatedAt = 0L,
    )

    private val libraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItemsAllSources(): Flow<List<LibraryItem>> = flowOf(listOf(hubItem))
        override fun observeAllLibraryItemsAllSources(): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem = hubItem
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(hubItem)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem = hubItem
        override fun observeItem(sourceId: String, itemId: String): Flow<LibraryItem?> = flowOf(hubItem)
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private val annotationsRepository = object : AnnotationsLibraryRepository {
        override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowOf(listOf(annotatedBook))
        override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> =
            flowOf(listOf(annotatedBook))
        override fun observeAnnotatedBooksAllSources(): Flow<List<AnnotatedBook>> = flowOf(listOf(annotatedBook))
    }

    private val sourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException("test fake"))
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
        override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = SyncNamespace.LocalOnly("test")
    }

    private val tokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    private val toReadRepository = object : ToReadRepository {
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun refresh(libraryId: String): Boolean = true
        override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = true
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
    }

    private val connectivityObserver = object : ConnectivityObserver {
        override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private lateinit var viewModel: RiffleViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = RiffleViewModel(
            libraryObserver = libraryObserver,
            sourceRepository = sourceRepository,
            tokenStorage = tokenStorage,
            toReadRepository = toReadRepository,
            annotationsLibraryRepository = annotationsRepository,
            connectivityObserver = connectivityObserver,
            offlineAvailability = object : LibraryItemOfflineAvailability {
                override fun isAvailableOffline(item: LibraryItem): Boolean = false
            },
            coverGridDensityStore = FakeCoverGridDensityStore,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingAnInProgressItemCallsOnItemSelected() = runComposeUiTest {
        var selectedSourceId: String? = null
        var selectedItemId: String? = null
        setContent {
            RiffleScreen(
                viewModel = viewModel,
                onOpenDrawer = {},
                onItemSelected = { sourceId, itemId ->
                    selectedSourceId = sourceId
                    selectedItemId = itemId
                },
            )
        }

        onNodeWithContentDescription("Hub Book").performClick()

        assertEquals("source1", selectedSourceId, "Tapping an in-progress item must fire onItemSelected with the source ID")
        assertEquals("item1", selectedItemId, "Tapping an in-progress item must fire onItemSelected with the item ID")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingAnAnnotatedBookCallsOnAnnotatedBookClick() = runComposeUiTest {
        var selectedSourceId: String? = null
        var selectedItemId: String? = null
        setContent {
            RiffleScreen(
                viewModel = viewModel,
                onOpenDrawer = {},
                onItemSelected = { _, _ -> },
                onAnnotatedBookClick = { sourceId, itemId ->
                    selectedSourceId = sourceId
                    selectedItemId = itemId
                },
            )
        }

        onNodeWithTag(TestTags.NAV_TAB_ANNOTATIONS).performClick()
        onNodeWithContentDescription("Annotated Title").performClick()

        assertEquals("source1", selectedSourceId, "Tapping an annotated book must fire onAnnotatedBookClick with the source ID")
        assertEquals("item1", selectedItemId, "Tapping an annotated book must fire onAnnotatedBookClick with the item ID")
    }
}

private object FakeCoverGridDensityStore : CoverGridDensityStore {
    override val scale = flowOf(1f)
    override suspend fun setScale(value: Float) {}
    override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket) = flowOf(1f)
    override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {}
}

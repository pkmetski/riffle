package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.Collection
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Series
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private val allItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val annotationsFlow = MutableStateFlow<List<Annotation>>(emptyList())

    private fun fakeRepo(): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun fakeAnnotationStore(): AnnotationStore = object : AnnotationStore {
        override fun observeHighlights(sourceId: String, itemId: String) = MutableStateFlow(emptyList<Annotation>())
        override fun observeBookmarks(sourceId: String, itemId: String) = MutableStateFlow(emptyList<Annotation>())
        override fun observeAnnotations(sourceId: String, itemId: String) = MutableStateFlow(emptyList<Annotation>())
        override fun observeAnnotationsForSource(sourceId: String) =
            annotationsFlow.map { all -> all.filter { it.sourceId == sourceId } }
        override suspend fun createHighlight(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, textBefore: String, textAfter: String, color: String, spineIndex: Int, progression: Double) = error("unused")
        override suspend fun createBookmark(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String) = error("unused")
        override suspend fun delete(id: String) = error("unused")
        override suspend fun recolor(id: String, color: String) = error("unused")
        override suspend fun updateNote(id: String, note: String?) = error("unused")
        override suspend fun renameBookmark(id: String, title: String) = error("unused")
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
    }

    private fun fakeAudiobookBookmarkStore(): com.riffle.core.domain.AudiobookBookmarkStore =
        object : com.riffle.core.domain.AudiobookBookmarkStore {
            override fun observe(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.AudiobookBookmark>())
            override fun observeForSource(sourceId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.AudiobookBookmark>())
            override fun observeHasUnsynced(sourceId: String, itemId: String) = MutableStateFlow(false)
            override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long) = error("unused")
            override suspend fun rename(id: String, title: String, now: Long) = error("unused")
            override suspend fun delete(id: String, now: Long) = error("unused")
        }

    private fun fakeServerRepository(): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Source? =
            Source("srv1", SourceUrl.parse("http://localhost")!!, true, false, "test")
        override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String = "test-token"
        override suspend fun deleteToken(sourceId: String) {}
    }

    private fun annotation(
        id: String,
        sourceId: String,
        itemId: String,
        textSnippet: String = "",
        note: String? = null,
        bookmarkTitle: String = "",
    ) = Annotation(
        id = id,
        sourceId = sourceId,
        itemId = itemId,
        type = "highlight",
        cfi = "",
        color = "yellow",
        note = note,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = bookmarkTitle,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun results_matchQueryScopedToLibrary() = runTest {
        allItemsFlow.value = listOf(
            LibraryItem(
                id = "b1", libraryId = "lib1", title = "Children of Dune", author = "Herbert",
                coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
                ebookFormat = EbookFormat.Epub, sourceId = "srv1",
            ),
        )
        annotationsFlow.value = listOf(
            annotation(id = "a1", sourceId = "srv1", itemId = "b1", textSnippet = "conscience"),
            annotation(id = "a2", sourceId = "srv1", itemId = "b1", textSnippet = "other"),
        )
        val savedState = SavedStateHandle(mapOf("libraryId" to "lib1", "query" to "conscience"))
        val vm = AnnotationSearchViewModel(savedState, fakeRepo(), fakeAnnotationStore(), fakeAudiobookBookmarkStore(), fakeServerRepository(), fakeTokenStorage())

        val results = vm.results.first { it.isNotEmpty() }
        assertEquals(listOf("a1"), results.map { it.annotation.id })
    }
}

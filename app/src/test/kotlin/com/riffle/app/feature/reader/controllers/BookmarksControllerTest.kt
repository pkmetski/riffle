package com.riffle.app.feature.reader.controllers

import android.net.FakeUri
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksControllerTest {

    // --- Fakes ---

    private class FakeAnnotationStore : AnnotationStore {
        val bookmarks = MutableStateFlow<List<Annotation>>(emptyList())
        val deleted = mutableListOf<String>()
        val created = mutableListOf<Annotation>()
        val renamed = mutableMapOf<String, String>()

        override fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>> = bookmarks
        override fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>> =
            MutableStateFlow(emptyList())
        override fun observeAnnotations(serverId: String, itemId: String): Flow<List<Annotation>> =
            MutableStateFlow(emptyList())
        override fun observeAnnotationsForServer(serverId: String): Flow<List<Annotation>> =
            MutableStateFlow(emptyList())

        override suspend fun createHighlight(
            serverId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, textBefore: String, textAfter: String, color: String,
            spineIndex: Int, progression: Double,
        ): Annotation {
            val a = Annotation(
                id = "highlight-${created.size}",
                serverId = serverId, itemId = itemId,
                type = "highlight",
                cfi = cfi, color = color, note = null,
                textSnippet = textSnippet, textBefore = textBefore, textAfter = textAfter,
                chapterHref = chapterHref, spineIndex = spineIndex, progression = progression,
                bookmarkTitle = "", createdAt = 0L, updatedAt = 0L,
            )
            created.add(a)
            return a
        }

        override suspend fun createBookmark(
            serverId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String,
        ): Annotation {
            val a = Annotation(
                id = "bm-${created.size}",
                serverId = serverId, itemId = itemId,
                type = "bookmark",
                cfi = cfi, color = "yellow", note = null,
                textSnippet = textSnippet, textBefore = "", textAfter = "",
                chapterHref = chapterHref, spineIndex = spineIndex, progression = progression,
                bookmarkTitle = bookmarkTitle, createdAt = 0L, updatedAt = 0L,
            )
            created.add(a)
            bookmarks.value = bookmarks.value + a
            return a
        }

        override suspend fun delete(id: String) {
            deleted.add(id)
            bookmarks.value = bookmarks.value.filter { it.id != id }
        }

        override suspend fun recolor(id: String, color: String) = Unit
        override suspend fun updateNote(id: String, note: String?) = Unit
        override suspend fun renameBookmark(id: String, title: String) { renamed[id] = title }
        override suspend fun findByItemAndCfi(serverId: String, itemId: String, cfi: String): Annotation? = null
    }

    private fun makeAnnotation(
        id: String = "a1",
        type: String = "bookmark",
        serverId: String = "srv",
        itemId: String = "item1",
        cfi: String = "epubcfi(/6/2)",
        chapterHref: String = "chapter1.xhtml",
        progression: Double = 0.0,
    ) = Annotation(
        id = id,
        serverId = serverId,
        itemId = itemId,
        type = type,
        cfi = cfi,
        color = "yellow",
        note = null,
        textSnippet = "",
        textBefore = "",
        textAfter = "",
        chapterHref = chapterHref,
        spineIndex = 0,
        progression = progression,
        bookmarkTitle = "Bookmark",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun makeController(
        store: FakeAnnotationStore = FakeAnnotationStore(),
        onScheduleSync: () -> Unit = {},
    ): Pair<BookmarksController, FakeAnnotationStore> {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val controller = BookmarksController(
            scope = scope,
            annotationStore = store,
            onScheduleSync = onScheduleSync,
        )
        return controller to store
    }

    // --- Tests ---

    @Test
    fun `bookmarkPositions reactively follows annotationStore observeBookmarks`() = runTest {
        val (controller, store) = makeController()
        val bm = makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.1)
        controller.bind("srv", "item1", MutableStateFlow(null))

        store.bookmarks.value = listOf(bm)

        val positions = controller.bookmarkPositions.first()
        assertEquals(1, positions.size)
        assertEquals("ch1.xhtml", positions[0].chapterHref)
        assertEquals(0.1, positions[0].progression, 0.001)
    }

    @Test
    fun `isCurrentPageBookmarked reflects bookmark presence at current href and progression`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        controller.bind("srv", "item1", currentLocator)

        assertFalse(controller.isCurrentPageBookmarked.value)

        // Add a bookmark at chapter1.xhtml, progression 0.1
        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "chapter1.xhtml", progression = 0.1))

        // Navigate to the same page
        currentLocator.value = buildLocator("chapter1.xhtml", 0.1)
        assertTrue(controller.isCurrentPageBookmarked.value)

        // Navigate away
        currentLocator.value = buildLocator("chapter2.xhtml", 0.0)
        assertFalse(controller.isCurrentPageBookmarked.value)
    }

    @Test
    fun `isCurrentPageBookmarked uses progression window tolerance`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        controller.bind("srv", "item1", currentLocator)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))
        // Within 5% tolerance → bookmarked
        currentLocator.value = buildLocator("ch1.xhtml", 0.52)
        assertTrue(controller.isCurrentPageBookmarked.value)

        // Outside tolerance → not bookmarked
        currentLocator.value = buildLocator("ch1.xhtml", 0.6)
        assertFalse(controller.isCurrentPageBookmarked.value)
    }

    @Test
    fun `renameBookmark updates store and calls sync`() = runTest {
        var syncCalled = false
        val (controller, store) = makeController(onScheduleSync = { syncCalled = true })
        controller.bind("srv", "item1", MutableStateFlow(null))

        controller.renameBookmark("bm-1", "New Title")

        assertEquals("New Title", store.renamed["bm-1"])
        assertTrue(syncCalled)
    }

    @Test
    fun `bind clears state from previous book`() = runTest {
        val (controller, store) = makeController()
        store.bookmarks.value = listOf(makeAnnotation())
        controller.bind("srv", "item1", MutableStateFlow(null))

        assertEquals(1, controller.bookmarkPositions.value.size)

        // Rebind to a new book with a fresh empty store
        store.bookmarks.value = emptyList()
        controller.bind("srv", "item2", MutableStateFlow(null))

        assertEquals(0, controller.bookmarkPositions.value.size)
    }

    @Test
    fun `bookmarkPositions are empty before bind is called`() = runTest {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val controller = BookmarksController(
            scope = scope,
            annotationStore = FakeAnnotationStore(),
            onScheduleSync = {},
        )
        assertEquals(emptyList<BookmarksController.BookmarkPosition>(), controller.bookmarkPositions.value)
    }

    // --- Helpers ---

    /**
     * Allocates a [Locator] without triggering [android.net.Uri] (not available in JVM tests).
     * Uses the same Unsafe + FakeUri pattern as [NavigationTargetTest].
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildLocator(href: String, progression: Double): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri(href))
        return Locator(
            href = url,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
        )
    }
}

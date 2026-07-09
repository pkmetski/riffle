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

        override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = bookmarks
        override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> =
            MutableStateFlow(emptyList())
        override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> =
            MutableStateFlow(emptyList())
        override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> =
            MutableStateFlow(emptyList())

        override suspend fun createHighlight(
            sourceId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, textBefore: String, textAfter: String, color: String,
            spineIndex: Int, progression: Double,
            embeddedFigures: List<com.riffle.core.domain.EmbeddedFigure>?,
        ): Annotation {
            val a = Annotation(
                id = "highlight-${created.size}",
                sourceId = sourceId, itemId = itemId,
                type = "highlight",
                cfi = cfi, color = color, note = null,
                textSnippet = textSnippet, textBefore = textBefore, textAfter = textAfter,
                chapterHref = chapterHref, spineIndex = spineIndex, progression = progression,
                bookmarkTitle = "", createdAt = 0L, updatedAt = 0L,
                embeddedFigures = embeddedFigures,
            )
            created.add(a)
            return a
        }

        override suspend fun createBookmark(
            sourceId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String,
        ): Annotation {
            val a = Annotation(
                id = "bm-${created.size}",
                sourceId = sourceId, itemId = itemId,
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

        override suspend fun createImageAnnotation(
            sourceId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, spineIndex: Int, progression: Double,
            imageHref: String?, imageSvg: String?, imageBytes: String?, color: String,
        ): Annotation {
            val a = Annotation(
                id = "img-${created.size}",
                sourceId = sourceId, itemId = itemId,
                type = "image",
                cfi = cfi, color = color, note = null,
                textSnippet = textSnippet, textBefore = "", textAfter = "",
                chapterHref = chapterHref, spineIndex = spineIndex, progression = progression,
                bookmarkTitle = "", createdAt = 0L, updatedAt = 0L,
                imageHref = imageHref, imageSvg = imageSvg,
            )
            created.add(a)
            return a
        }

        override suspend fun delete(id: String) {
            deleted.add(id)
            bookmarks.value = bookmarks.value.filter { it.id != id }
        }

        override suspend fun recolor(id: String, color: String) = Unit
        override suspend fun updateNote(id: String, note: String?) = Unit
        override suspend fun renameBookmark(id: String, title: String) { renamed[id] = title }
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
        override suspend fun findImageAnnotationForFigure(
            sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?,
        ): Annotation? = null
    }

    private fun makeAnnotation(
        id: String = "a1",
        type: String = "bookmark",
        sourceId: String = "srv",
        itemId: String = "item1",
        cfi: String = "epubcfi(/6/2)",
        chapterHref: String = "chapter1.xhtml",
        progression: Double = 0.0,
    ) = Annotation(
        id = id,
        sourceId = sourceId,
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
        controller.bind("srv", "item1", MutableStateFlow(null), MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap<String, Double>()))

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
        controller.bind("srv", "item1", currentLocator, MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap()))

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
        controller.bind("srv", "item1", currentLocator, MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap()))

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))
        // Within 5% tolerance → bookmarked
        currentLocator.value = buildLocator("ch1.xhtml", 0.52)
        assertTrue(controller.isCurrentPageBookmarked.value)

        // Outside tolerance → not bookmarked
        currentLocator.value = buildLocator("ch1.xhtml", 0.6)
        assertFalse(controller.isCurrentPageBookmarked.value)
    }

    @Test
    fun `continuous indicator uses the widened 33% window after onOrientationChanged`() = runTest {
        // The continuous-mode eps is BOOKMARK_VIEWPORT_EPS (33%), which absorbs the offset
        // between the viewport-midpoint progression locatorAt emits and where the bookmark
        // anchor actually sits inside the visible viewport. 33% covers short chapters where
        // viewportFraction can hit ~0.6 (so vf/2 ≈ 0.30) — the exact case the user reproduced.
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        controller.bind("srv", "item1", currentLocator, MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap()))
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.0))
        // The exact logcat snapshot the user reproduced — at heading-at-top in a short chapter
        // the midpoint sits ~0.2585 past the saved bookmark midpoint. 33% catches it.
        currentLocator.value = buildLocator("ch1.xhtml", 0.2585)
        assertTrue(
            "indicator must be ON when the bookmark anchor is at the viewport top edge",
            controller.isCurrentPageBookmarked.value,
        )

        // A bookmark well past the 33% window is genuinely off-page.
        currentLocator.value = buildLocator("ch1.xhtml", 0.60)
        assertFalse(controller.isCurrentPageBookmarked.value)
    }

    @Test
    fun `onOrientationChanged toggles eps without rebinding`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        controller.bind("srv", "item1", currentLocator, MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap()))
        // Start in paginated.
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Horizontal)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))
        currentLocator.value = buildLocator("ch1.xhtml", 0.70)
        // Paginated → tight 5% eps misses by 0.20.
        assertFalse(controller.isCurrentPageBookmarked.value)

        // Flip to continuous → 33% eps catches.
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)
        assertTrue(controller.isCurrentPageBookmarked.value)
    }

    @Test
    fun `renameBookmark updates store and calls sync`() = runTest {
        var syncCalled = false
        val (controller, store) = makeController(onScheduleSync = { syncCalled = true })
        controller.bind("srv", "item1", MutableStateFlow(null), MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap<String, Double>()))

        controller.renameBookmark("bm-1", "New Title")

        assertEquals("New Title", store.renamed["bm-1"])
        assertTrue(syncCalled)
    }

    @Test
    fun `bind clears state from previous book`() = runTest {
        val (controller, store) = makeController()
        store.bookmarks.value = listOf(makeAnnotation())
        controller.bind("srv", "item1", MutableStateFlow(null), MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap<String, Double>()))

        assertEquals(1, controller.bookmarkPositions.value.size)

        // Rebind to a new book with a fresh empty store
        store.bookmarks.value = emptyList()
        controller.bind("srv", "item2", MutableStateFlow(null), MutableStateFlow(emptyList<String>() to emptyList()), MutableStateFlow(emptyMap()))

        assertEquals(0, controller.bookmarkPositions.value.size)
    }

    // Regression for the "bookmark stays lit for 3-4 pages" bug: the old fixed 5% eps was ~3 pages
    // wide on a typical (~60-position) chapter. The page-aware eps computes `0.5 / positions`
    // — half a page — so the indicator lights ONLY for the bookmarked page (not its neighbours).
    // This test pins the tight window for a 60-page chapter: a 5% delta must NOT light.
    @Test
    fun `paginated eps narrows to half-a-page when spine position counts are known`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        val positions = MutableStateFlow(
            listOf("ch1.xhtml") to listOf(60),
        )
        controller.bind("srv", "item1", currentLocator, positions, MutableStateFlow(emptyMap()))
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Horizontal)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))

        // Same page (delta 0.003 < 1/(2*60) = 0.0083) → lit.
        currentLocator.value = buildLocator("ch1.xhtml", 0.503)
        assertTrue("indicator ON for the same page (0.003 delta, half-page = 0.0083)", controller.isCurrentPageBookmarked.value)

        // 3 pages away (delta 0.05, the OLD fixed eps) → NOT lit any more. Fail-red asserts the
        // regression is fixed; if BOOKMARK_PAGE_EPS were still 0.05 this would flip green.
        currentLocator.value = buildLocator("ch1.xhtml", 0.55)
        assertFalse("indicator OFF for 3 pages away (0.05 delta on a 60-page chapter)", controller.isCurrentPageBookmarked.value)
    }

    // Publication positions can arrive AFTER bind (positionsByReadingOrder is computed
    // asynchronously by Readium after publication load). Until they land, the controller MUST
    // fall back to the fixed 5% window rather than a divide-by-zero or an over-tight zero eps.
    @Test
    fun `paginated eps falls back to 5% when spine position counts are not yet available`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        controller.bind(
            "srv", "item1", currentLocator,
            MutableStateFlow(emptyList<String>() to emptyList()),
            MutableStateFlow(emptyMap()),
        )
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Horizontal)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))
        currentLocator.value = buildLocator("ch1.xhtml", 0.52)
        assertTrue("indicator ON within the 5% fallback (0.02 delta)", controller.isCurrentPageBookmarked.value)
    }

    // Regression for the "bookmark stays lit for several screens" symptom in continuous mode.
    // The old flat 33% eps kept a bookmark at midpoint 0.5 lit across progression 0.17-0.83.
    // Continuous eps is now `1 / positions` (full-viewport tolerance — see `bookmarkEpsFor`
    // for the geometric argument covering `alignToTop=true` bookmark-nav landings), so on a
    // 40-position chapter the indicator lights within ±0.025 progression — about one viewport
    // in either direction, not several.
    @Test
    fun `continuous eps is one-viewport wide when spine position counts are known`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        val positions = MutableStateFlow(listOf("ch1.xhtml") to listOf(40))
        controller.bind("srv", "item1", currentLocator, positions, MutableStateFlow(emptyMap()))
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))

        // Within one viewport (delta 0.020 < 1/40 = 0.025) → lit.
        currentLocator.value = buildLocator("ch1.xhtml", 0.520)
        assertTrue("indicator ON at 0.020 midpoint delta on a 40-position chapter", controller.isCurrentPageBookmarked.value)

        // Beyond one viewport (delta 0.10 — the OLD 33% flat eps would have kept this lit) → NOT lit.
        currentLocator.value = buildLocator("ch1.xhtml", 0.60)
        assertFalse("indicator OFF at 0.10 midpoint delta on a 40-position chapter", controller.isCurrentPageBookmarked.value)
    }

    // The bookmarkEpsFor(chapterHref) helper is what toggleBookmark reads. It MUST return the
    // same value the indicator's combine consumes, so a "delete if already bookmarked" check
    // can't match a bookmark 3 pages away. This test pins the wire.
    @Test
    fun `bookmarkEpsFor returns half-a-page for paginated chapters with known position counts`() = runTest {
        val (controller, _) = makeController()
        val positions = MutableStateFlow(
            listOf("ch1.xhtml", "ch2.xhtml") to listOf(60, 20),
        )
        controller.bind("srv", "item1", MutableStateFlow(null), positions, MutableStateFlow(emptyMap()))
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Horizontal)

        assertEquals("60-page chapter: half-a-page = 1/120", 1.0 / 120.0, controller.bookmarkEpsFor("ch1.xhtml"), 1e-9)
        assertEquals("20-page chapter: half-a-page = 1/40", 1.0 / 40.0, controller.bookmarkEpsFor("ch2.xhtml"), 1e-9)
        assertEquals("unknown chapter falls back to 5%", 0.05, controller.bookmarkEpsFor("ch99.xhtml"), 1e-9)
    }

    // Issue #399: the live viewport-fraction path is the geometrically-correct bound. In
    // continuous mode eps == fraction (full viewport). Positions is a rough char-slice proxy
    // and loses to fraction whenever present.
    @Test
    fun `live viewport fraction takes precedence over spine position counts`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        val positions = MutableStateFlow(listOf("ch1.xhtml") to listOf(30))
        val fractions = MutableStateFlow(mapOf("ch1.xhtml" to 0.20)) // continuous eps = 0.20
        controller.bind("srv", "item1", currentLocator, positions, fractions)
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.5))

        // 0.19 delta — inside the continuous fraction=0.20 window, but well outside 1/30=0.033.
        // Assertion pins fraction wins over positions.
        currentLocator.value = buildLocator("ch1.xhtml", 0.69)
        assertTrue(
            "indicator ON at 0.19 delta when fraction=0.20 (continuous eps=0.20)",
            controller.isCurrentPageBookmarked.value,
        )

        // 0.21 delta — outside the fraction=0.20 window.
        currentLocator.value = buildLocator("ch1.xhtml", 0.71)
        assertFalse(
            "indicator OFF at 0.21 delta when fraction=0.20 (continuous eps=0.20)",
            controller.isCurrentPageBookmarked.value,
        )
    }

    // The user's principled contract: after navigating to a bookmark, the indicator MUST light
    // — anything less is a disagreement between the reader and the annotation panel.
    //
    // In continuous mode with `alignToTop=true` bookmark-panel navigation, the arrival midpoint
    // shifts up to a full `viewportFraction` from the saved midpoint (the anchor can sit
    // anywhere in the saved viewport — top edge → arrival delta 0; bottom edge → arrival delta
    // one viewportFraction). Tightening eps to `fraction / 2` (as the paginated logic does)
    // would flake the indicator off for any bookmark whose anchor sat in the lower half of the
    // saved viewport, which is the majority case — users bookmark from the reading zone (the
    // middle-to-bottom of the visible page).
    //
    // This test pins the worst-case arrival delta and asserts the indicator stays lit.
    @Test
    fun `continuous indicator lights at the alignToTop bookmark-nav arrival boundary`() = runTest {
        val (controller, store) = makeController()
        val currentLocator = MutableStateFlow<Locator?>(null)
        val fractions = MutableStateFlow(mapOf("ch1.xhtml" to 0.10))
        controller.bind(
            "srv", "item1", currentLocator,
            MutableStateFlow(emptyList<String>() to emptyList()),
            fractions,
        )
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)

        store.bookmarks.value = listOf(makeAnnotation(chapterHref = "ch1.xhtml", progression = 0.50))
        // Saved midpoint 0.50, viewportFraction 0.10 → arrival midpoint 0.50 + 0.10 = 0.60 when
        // the saved anchor sat at the bottom edge of its viewport. Delta = 0.10 == eps
        // (`fraction`). The `<= eps` boundary check MUST keep the indicator ON — if it flips
        // OFF here, revert the eps widening to `fraction / 2` at your peril; the user's
        // observed "not lit after nav" symptom will return.
        currentLocator.value = buildLocator("ch1.xhtml", 0.60)
        assertTrue(
            "indicator MUST be ON at the alignToTop arrival boundary (delta == fraction)",
            controller.isCurrentPageBookmarked.value,
        )
    }

    // When no fraction has arrived yet for the current chapter, positions is the fallback.
    // Guards against the priority order accidentally short-circuiting on an empty map.
    @Test
    fun `bookmarkEpsFor falls back to positions when fraction is not yet measured`() = runTest {
        val (controller, _) = makeController()
        val positions = MutableStateFlow(listOf("ch1.xhtml") to listOf(60))
        controller.bind(
            "srv", "item1", MutableStateFlow(null), positions,
            MutableStateFlow(emptyMap()), // no fraction reported yet
        )
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Horizontal)

        assertEquals(
            "empty fraction map → 0.5/positions still applies",
            1.0 / 120.0,
            controller.bookmarkEpsFor("ch1.xhtml"),
            1e-9,
        )
    }

    // Fractions are chapter-scoped: a fraction for chapter A must not leak into chapter B.
    // (An early draft keyed the map on the wrong href would silently pass every other test.)
    @Test
    fun `live viewport fraction is chapter-scoped`() = runTest {
        val (controller, _) = makeController()
        val positions = MutableStateFlow(
            listOf("ch1.xhtml", "ch2.xhtml") to listOf(50, 50),
        )
        val fractions = MutableStateFlow(mapOf("ch1.xhtml" to 0.10)) // continuous eps = fraction = 0.10
        controller.bind("srv", "item1", MutableStateFlow(null), positions, fractions)
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)

        assertEquals("ch1 uses fraction (continuous eps = fraction)", 0.10, controller.bookmarkEpsFor("ch1.xhtml"), 1e-9)
        assertEquals("ch2 falls back to positions (continuous 1/50)", 0.02, controller.bookmarkEpsFor("ch2.xhtml"), 1e-9)
    }

    // A zero or negative fraction (measurement race, degenerate chapter) MUST fall through to
    // positions rather than short-circuiting to eps=0 (which would silently kill the indicator).
    @Test
    fun `zero or negative fraction is ignored`() = runTest {
        val (controller, _) = makeController()
        val positions = MutableStateFlow(listOf("ch1.xhtml") to listOf(40))
        val fractions = MutableStateFlow(mapOf("ch1.xhtml" to 0.0))
        controller.bind("srv", "item1", MutableStateFlow(null), positions, fractions)
        controller.onOrientationChanged(com.riffle.core.domain.ReaderOrientation.Continuous)

        assertEquals(
            "zero fraction is dropped, positions used instead (continuous eps = 1/positions)",
            1.0 / 40.0,
            controller.bookmarkEpsFor("ch1.xhtml"),
            1e-9,
        )
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

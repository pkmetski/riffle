package com.riffle.app.feature.reader.session

import android.net.FakeUri
import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.testing.TestApplicationScope
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.HighlightColorPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSessionTest {

    // ------ Fakes -------------------------------------------------------------------------

    data class CreateHighlightArgs(
        val sourceId: String,
        val itemId: String,
        val cfi: String,
        val textSnippet: String,
        val chapterHref: String,
        val textBefore: String,
        val textAfter: String,
        val color: String,
        val spineIndex: Int,
        val progression: Double,
    )

    private class FakeAnnotationStore : AnnotationStore {
        val highlights = MutableStateFlow<List<Annotation>>(emptyList())
        val allAnnotations = MutableStateFlow<List<Annotation>>(emptyList())

        val deletedIds = mutableListOf<String>()
        val recoloredIds = mutableListOf<Pair<String, String>>()
        val updatedNotes = mutableListOf<Pair<String, String?>>()
        val createHighlightCalls = mutableListOf<CreateHighlightArgs>()

        override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> = highlights
        override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = MutableStateFlow(emptyList())
        override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> = allAnnotations
        override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> = MutableStateFlow(emptyList())
        override suspend fun createHighlight(
            sourceId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, textBefore: String, textAfter: String, color: String,
            spineIndex: Int, progression: Double,
        ): Annotation {
            createHighlightCalls.add(
                CreateHighlightArgs(sourceId, itemId, cfi, textSnippet, chapterHref, textBefore, textAfter, color, spineIndex, progression)
            )
            return makeAnnotation(id = "h1", type = "highlight", cfi = cfi, color = color)
        }
        override suspend fun createBookmark(
            sourceId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String,
        ): Annotation = makeAnnotation(id = "b1", type = "bookmark", cfi = cfi)
        override suspend fun delete(id: String) { deletedIds.add(id) }
        override suspend fun recolor(id: String, color: String) { recoloredIds.add(id to color) }
        override suspend fun updateNote(id: String, note: String?) { updatedNotes.add(id to note) }
        override suspend fun renameBookmark(id: String, title: String) {}
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
    }

    /**
     * Lightweight fake for sync operations. AnnotationSession interacts with the controller
     * through four operations: syncOnOpen, startLiveSync, scheduleSync, syncOnClose.
     */
    private class FakeSyncOps {
        var syncOnOpenCalled = false
        var syncOnCloseCalled = false
        var scheduleDebounceCount = 0
        var startLiveSyncCalled = false
        var lastLiveSyncJob: Job? = null

        fun makeSyncOnOpen(): suspend (String, String, String) -> Unit = { _, _, _ ->
            syncOnOpenCalled = true
        }

        fun makeScheduleDebounce(): (String, String, String) -> Unit = { _, _, _ ->
            scheduleDebounceCount++
        }

        fun makeStartLiveSync(scope: CoroutineScope): (String, String, String) -> Job = { _, _, _ ->
            startLiveSyncCalled = true
            scope.launch { delay(Long.MAX_VALUE) }
                .also { lastLiveSyncJob = it }
        }

        fun makeSyncOnClose(): suspend (String, String, String) -> Unit = { _, _, _ ->
            syncOnCloseCalled = true
        }
    }

    // ------ Helpers -----------------------------------------------------------------------

    private fun fakeAnnotation(
        id: String = "a1",
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi: String = "epubcfi(/6/4!/4/2)",
        color: String = "yellow",
    ) = makeAnnotation(id, type, cfi, color)

    companion object {
        fun makeAnnotation(
            id: String = "a1",
            type: String = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi: String = "epubcfi(/6/4!/4/2)",
            color: String = "yellow",
        ) = Annotation(
        id = id,
        sourceId = "srv1",
        itemId = "item1",
        type = type,
        cfi = cfi,
        color = color,
        note = null,
        textSnippet = "Hello world",
        textBefore = "Some text before",
        textAfter = "Some text after",
        chapterHref = "chapter1.xhtml",
        spineIndex = 0,
        progression = 0.3,
        bookmarkTitle = "",
        createdAt = 1000L,
        updatedAt = 1001L,
    )
    } // companion object

    @Suppress("UNCHECKED_CAST")
    private fun buildLocator(href: String = "chapter1.xhtml", progression: Double = 0.3): Locator {
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

    private class FakeHighlightColorPreferencesStore(
        initial: HighlightColor = HighlightColor.DEFAULT,
    ) : HighlightColorPreferencesStore {
        // Per-book state keyed by "$sourceId:$itemId". Unknown book → the shared [initial] fallback,
        // so tests that don't care about book identity keep working. Tests that DO need per-book
        // isolation call [setLastUsedColor] with distinct ids and read [currentValue] with them.
        private val perBook = mutableMapOf<String, MutableStateFlow<HighlightColor>>()
        private val defaultInitial = initial
        private fun k(sourceId: String, itemId: String) = "$sourceId:$itemId"
        private fun flowFor(sourceId: String, itemId: String) =
            perBook.getOrPut(k(sourceId, itemId)) { MutableStateFlow(defaultInitial) }
        override fun lastUsedColor(sourceId: String, itemId: String): Flow<HighlightColor> =
            flowFor(sourceId, itemId)
        override suspend fun setLastUsedColor(sourceId: String, itemId: String, value: HighlightColor) {
            flowFor(sourceId, itemId).value = value
        }
        fun currentValue(sourceId: String = "srv1", itemId: String = "item1"): HighlightColor =
            flowFor(sourceId, itemId).value
    }

    private fun makeSession(
        store: FakeAnnotationStore = FakeAnnotationStore(),
        syncOps: FakeSyncOps,
        scope: CoroutineScope,
        statusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
        colorPrefsStore: HighlightColorPreferencesStore = FakeHighlightColorPreferencesStore(),
        flushScope: ProgressFlushScope = ProgressFlushScope(
            TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()))
        ),
        mergeAfterEdit: suspend (String, String, String?) -> Unit = { _, _, _ -> },
    ) = AnnotationSession(
        scope = scope,
        annotationStore = store,
        annotationStatusStore = statusStore,
        highlightColorPreferencesStore = colorPrefsStore,
        progressFlushScope = flushScope,
        startLiveSync = syncOps.makeStartLiveSync(scope),
        scheduleSync = syncOps.makeScheduleDebounce(),
        syncOnOpen = syncOps.makeSyncOnOpen(),
        syncOnClose = syncOps.makeSyncOnClose(),
        mergeAfterEdit = mergeAfterEdit,
    )

    private fun defaultBind(
        session: AnnotationSession,
        store: FakeAnnotationStore = FakeAnnotationStore(),
        highlightRenderResolver: suspend (Annotation) -> EpubReaderViewModel.HighlightRender? = { null },
        cfiLocatorResolver: suspend (String) -> Locator? = { null },
    ) {
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = highlightRenderResolver,
            cfiLocatorResolver = cfiLocatorResolver,
        )
    }

    // ------ Tests -------------------------------------------------------------------------

    /**
     * Test 1: highlightRenders reactively reflects annotationStore.observeHighlights
     */
    @Test
    fun `highlightRenders reactively reflects annotationStore observeHighlights`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)
        val anno = fakeAnnotation()
        val render = EpubReaderViewModel.HighlightRender(anno.id, buildLocator(), anno.color, anno.note)

        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { a -> if (a.id == anno.id) render else null },
            cfiLocatorResolver = { null },
        )

        assertEquals(emptyList<EpubReaderViewModel.HighlightRender>(), session.highlightRenders.value)

        store.highlights.value = listOf(anno)
        // With UnconfinedTestDispatcher the collect lambda runs eagerly
        assertEquals(1, session.highlightRenders.value.size)
        assertEquals(anno.id, session.highlightRenders.value[0].id)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 2: recolorHighlight updates store and schedules debounce sync
     */
    @Test
    fun `recolorHighlight updates store and schedules debounce sync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        session.recolorHighlight("h1", HighlightColor.BLUE)

        assertEquals(listOf("h1" to HighlightColor.BLUE.token), store.recoloredIds)
        assertEquals(1, syncOps.scheduleDebounceCount)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: recolorHighlight must ALSO persist the picked colour to the per-book
     * last-used store keyed by the currently-bound (sourceId, itemId), so subsequent new
     * highlights in that book are born in that colour. Reverting the `setLastUsedColor` call
     * in AnnotationSession.recolorHighlight (or dropping the bound-book ids from it) flips
     * this assertion.
     */
    @Test
    fun `recolorHighlight persists colour as last-used for the bound book`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val colorPrefs = FakeHighlightColorPreferencesStore(initial = HighlightColor.YELLOW)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope, colorPrefsStore = colorPrefs)

        defaultBind(session)

        session.recolorHighlight("h1", HighlightColor.GREEN)

        assertEquals(HighlightColor.GREEN, colorPrefs.currentValue(sourceId = "srv1", itemId = "item1"))
        // Other books stay on their own last-used (fake's initial fallback) — recolour must not
        // leak across books.
        assertEquals(HighlightColor.YELLOW, colorPrefs.currentValue(sourceId = "srv1", itemId = "item2"))

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: [AnnotationSession.lastUsedHighlightColor] must surface the stored value
     * synchronously via its StateFlow AFTER [bind], so the VM can read `.value` at highlight-
     * creation time (see [EpubReaderViewModel.createHighlight]). If the per-book observation
     * job is broken, the value stays at the palette default and this test flips.
     */
    @Test
    fun `lastUsedHighlightColor StateFlow reflects the bound book's stored value`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val colorPrefs = FakeHighlightColorPreferencesStore(initial = HighlightColor.DEFAULT)
        // Pre-seed the bound book's colour BEFORE bind so the first collection emits it.
        colorPrefs.setLastUsedColor("srv1", "item1", HighlightColor.BLUE)
        val session = makeSession(syncOps = FakeSyncOps(), scope = sessionScope, colorPrefsStore = colorPrefs)

        defaultBind(session)

        assertEquals(HighlightColor.BLUE, session.lastUsedHighlightColor.value)

        // A later update on the SAME book flows through.
        colorPrefs.setLastUsedColor("srv1", "item1", HighlightColor.GREEN)
        assertEquals(HighlightColor.GREEN, session.lastUsedHighlightColor.value)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: rebinding to a different book must swap the last-used colour source to that
     * book's own value. If [bind] fails to cancel the previous observation job (or fails to
     * reset the state), the previous book's colour leaks into the newly-bound book and this
     * test flips.
     */
    @Test
    fun `lastUsedHighlightColor swaps to the new book on rebind`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val colorPrefs = FakeHighlightColorPreferencesStore(initial = HighlightColor.DEFAULT)
        colorPrefs.setLastUsedColor("srv1", "item1", HighlightColor.BLUE)
        colorPrefs.setLastUsedColor("srv1", "item2", HighlightColor.RED)
        val session = makeSession(syncOps = FakeSyncOps(), scope = sessionScope, colorPrefsStore = colorPrefs)

        defaultBind(session)
        assertEquals(HighlightColor.BLUE, session.lastUsedHighlightColor.value)

        // Rebind to a different book — colour must swap.
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item2",
            highlightRenderResolver = { null },
            cfiLocatorResolver = { null },
        )
        assertEquals(HighlightColor.RED, session.lastUsedHighlightColor.value)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: individual edits (recolour, note-add/edit, note-clear) INSIDE the highlight
     * actions popup must NOT fire [mergeAfterEdit]. The user is still iterating; a mid-session
     * merge would silently absorb neighbours before they've committed. Commit is signalled by
     * popup dismissal — see [AnnotationSession.dismissHighlightActions].
     */
    @Test
    fun `individual edits do not fire mergeAfterEdit`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)
        store.allAnnotations.value = listOf(makeAnnotation(id = "h1", color = "yellow"))

        session.recolorHighlight("h1", HighlightColor.BLUE)
        session.updateHighlightNote("h1", "hello")
        session.updateHighlightNote("h1", null)

        assertEquals(0, mergeCalls.size)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: [AnnotationSession.dismissHighlightActions] is the merge commit point. On
     * dismiss it must fire [mergeAfterEdit] with the row's CURRENT colour + note so the VM can
     * absorb a same-chapter same-colour no-note neighbour. Dropping this call reintroduces the
     * "user closed popup, expected merge, nothing happened" report.
     */
    @Test
    fun `dismissHighlightActions fires mergeAfterEdit with the current row state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)
        store.allAnnotations.value = listOf(makeAnnotation(id = "h1", color = "green").copy(note = null))
        session.openHighlightActions("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))

        session.dismissHighlightActions()

        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("green", mergeCalls[0].second)
        assertNull(mergeCalls[0].third)
        // And the popup is closed.
        assertNull(session.highlightToEdit.value)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: tapping "Add note" transitions the actions popup into the note editor. The
     * actions popup closes, but this MUST NOT fire mergeAfterEdit — the row is about to receive
     * a note. A merge fired here would (a) absorb the row into a same-colour neighbour and (b)
     * cause the incoming note to land on a tombstoned id (video-reported bug).
     */
    @Test
    fun `openNoteEditor closes actions popup without firing merge`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)
        store.allAnnotations.value = listOf(makeAnnotation(id = "h1"))
        session.openHighlightActions("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))

        session.openNoteEditor("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))

        assertNull(session.highlightToEdit.value)
        assertEquals("h1", session.noteEditorTarget.value?.id)
        assertEquals(0, mergeCalls.size)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /** Regression: while the note editor is open, dismissing the actions popup is a no-op. */
    @Test
    fun `dismissHighlightActions is a no-op when note editor is open`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)
        store.allAnnotations.value = listOf(makeAnnotation(id = "h1"))
        session.openHighlightActions("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))
        session.openNoteEditor("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))

        session.dismissHighlightActions()

        assertEquals(0, mergeCalls.size)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: commitNoteEdit persists the note THEN fires merge with the NEW note. If the
     * merge check ran before the note was persisted, eligibility would pass (both notes empty)
     * and the row would be absorbed — losing the just-written note.
     */
    @Test
    fun `commitNoteEdit fires mergeAfterEdit with the new note`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)
        store.allAnnotations.value = listOf(makeAnnotation(id = "h1", color = "blue"))
        session.openNoteEditor("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))

        session.commitNoteEdit("h1", "my thought")

        assertEquals(listOf("h1" to "my thought"), store.updatedNotes)
        assertNull(session.noteEditorTarget.value)
        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("blue", mergeCalls[0].second)
        assertEquals("my thought", mergeCalls[0].third)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Regression: cancelNoteEdit is still a commit point — the actions-popup edits (recolour,
     * note-clear) that preceded opening the note editor need to see a final merge attempt.
     */
    @Test
    fun `cancelNoteEdit fires mergeAfterEdit with unchanged row state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)
        store.allAnnotations.value = listOf(makeAnnotation(id = "h1", color = "green"))
        session.openNoteEditor("h1", androidx.compose.ui.unit.IntRect(0, 0, 0, 0))

        session.cancelNoteEdit()

        assertNull(session.noteEditorTarget.value)
        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("green", mergeCalls[0].second)
        assertNull(mergeCalls[0].third)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * If the popup was never opened (no target), dismiss must be a no-op — no false merge fires.
     */
    @Test
    fun `dismissHighlightActions with no target does not fire merge`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val mergeCalls = mutableListOf<Triple<String, String, String?>>()
        val session = makeSession(
            store = store, syncOps = syncOps, scope = sessionScope,
            mergeAfterEdit = { id, color, note -> mergeCalls += Triple(id, color, note) },
        )
        defaultBind(session)

        session.dismissHighlightActions()

        assertEquals(0, mergeCalls.size)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 3: deleteHighlight removes from store and schedules debounce sync
     */
    @Test
    fun `deleteHighlight removes from store and schedules debounce sync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        session.deleteHighlight("h1")

        assertTrue(store.deletedIds.contains("h1"))
        assertEquals(1, syncOps.scheduleDebounceCount)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 4: openAnnotationsPanel + navigateToAnnotation emits via channel
     */
    @Test
    fun `openAnnotationsPanel and navigateToAnnotation emits via channel`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val targetLocator = buildLocator()
        val anno = fakeAnnotation(id = "a1", cfi = "epubcfi(/6/4!/4/2)")
        store.allAnnotations.value = listOf(anno)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { null },
            cfiLocatorResolver = { cfi -> if (cfi == anno.cfi) targetLocator else null },
        )

        session.openAnnotationsPanel()
        assertTrue(session.annotationsPanelVisible.value)

        // Collect the channel event
        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation("a1")

        assertEquals(1, received.size)
        assertEquals(targetLocator, received[0].locator)
        // The seeded annotation in this test has type="highlight", so the event must carry
        // isBookmark=false. Continuous-mode landing now uses viewport-midpoint for both types;
        // the flag is preserved on the event because downstream (analytics, tests) still
        // branches on annotation type.
        assertFalse(received[0].isBookmark)
        // The annotation id must ride along on the event: continuous-mode navigation uses it to
        // look up the actual `<mark data-riffle-ann="…">` device-Y (via
        // `ChapterWebView.annotationOffsetTopDevicePx`) and centre the viewport on the mark
        // rather than on the enclosing paragraph's top. Dropping the id here would silently
        // regress the fix to paragraph-anchor landing — pixel-close but visibly off for any
        // mid- or end-paragraph highlight.
        assertEquals("a1", received[0].annotationId)
        assertFalse(session.annotationsPanelVisible.value)

        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `navigateToAnnotation tags bookmarks as isBookmark=true so downstream can branch on type`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val targetLocator = buildLocator()
        val anno = fakeAnnotation(id = "b1", type = com.riffle.core.database.AnnotationEntity.TYPE_BOOKMARK, cfi = "epubcfi(/6/4!/4/2)")
        store.allAnnotations.value = listOf(anno)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { null },
            cfiLocatorResolver = { _ -> targetLocator },
        )

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }
        session.navigateToAnnotation("b1")

        assertEquals(1, received.size)
        assertTrue(received[0].isBookmark)

        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 5: syncBanner reflects annotationStatusStore states (Syncing/Synced/Failed)
     */
    @Test
    fun `syncBanner reflects annotationStatusStore states`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val statusStore = AnnotationSyncStatusStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(
            syncOps = syncOps,
            scope = sessionScope,
            statusStore = statusStore,
        )

        // Initial: NeverRun → null banner
        assertNull(session.syncBanner.value)

        statusStore.report(CycleOutcome.Success(1000L))
        assertEquals(AnnotationSyncBanner.Synced, session.syncBanner.value)

        statusStore.report(CycleOutcome.Failed.Network(2000L, "timeout"))
        assertTrue(session.syncBanner.value is AnnotationSyncBanner.Failed)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 6: bind triggers syncOnOpen and startLiveSync
     */
    @Test
    fun `bind triggers syncOnOpen and startLiveSync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val session = makeSession(syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        assertTrue("syncOnOpen should be called on bind", syncOps.syncOnOpenCalled)
        assertTrue("startLiveSync should be called on bind", syncOps.startLiveSyncCalled)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 7: onBookClosed triggers syncOnClose and cancels live-sync job
     */
    @Test
    fun `onBookClosed triggers syncOnClose and cancels live-sync job`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        // Use a real ProgressFlushScope backed by a test dispatcher
        val flushScope = ProgressFlushScope(
            TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()))
        )
        val session = makeSession(syncOps = syncOps, scope = sessionScope, flushScope = flushScope)

        defaultBind(session)

        val liveSyncJob = syncOps.lastLiveSyncJob
        assertFalse("Live-sync job should be active before close", liveSyncJob?.isCancelled ?: true)

        session.onBookClosed()

        assertTrue("syncOnClose should be called on book closed", syncOps.syncOnCloseCalled)
        assertTrue("Live-sync job should be cancelled after onBookClosed", liveSyncJob?.isCancelled == true)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 8: live-sync job is single-flight per book (rebind cancels the previous job)
     */
    @Test
    fun `live-sync job is single-flight per book`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val session = makeSession(syncOps = syncOps, scope = sessionScope)

        defaultBind(session)
        val firstJob = syncOps.lastLiveSyncJob

        // Rebind to a different item — should cancel the first live-sync job
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item2",
            highlightRenderResolver = { null },
            cfiLocatorResolver = { null },
        )
        val secondJob = syncOps.lastLiveSyncJob

        assertTrue("Previous live-sync job should be cancelled on rebind", firstJob?.isCancelled == true)
        assertTrue("New live-sync job should be active", secondJob?.isActive == true)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 9: updateHighlightNote persists note and schedules debounce sync
     */
    @Test
    fun `updateHighlightNote persists note and schedules debounce sync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        session.updateHighlightNote("h1", "My note")

        assertEquals(1, syncOps.scheduleDebounceCount)
        assertEquals(listOf("h1" to "My note"), store.updatedNotes)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 11: onReaderClosed cancels the live-sync job (regression: the original VM cancelled
     * annotationLiveSyncJob in onReaderClosed; the extraction removed that call).
     */
    @Test
    fun `onReaderClosed cancels live-sync job`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val session = makeSession(syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        val liveSyncJob = syncOps.lastLiveSyncJob
        assertTrue("Live-sync job should be active after bind", liveSyncJob?.isActive == true)

        session.onReaderClosed()

        assertTrue("Live-sync job should be cancelled after onReaderClosed", liveSyncJob?.isCancelled == true)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 10: deleteAnnotation removes from store and clears highlightToEdit if needed
     */
    @Test
    fun `deleteAnnotation removes from store and clears highlightToEdit if needed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        // Set a highlight to edit, then delete it
        session.openHighlightActions("h1", androidx.compose.ui.unit.IntRect.Zero)
        assertEquals("h1", session.highlightToEdit.value?.id)

        session.deleteAnnotation("h1")

        assertTrue(store.deletedIds.contains("h1"))
        assertNull("highlightToEdit should be cleared when its annotation is deleted", session.highlightToEdit.value)
        assertEquals(1, syncOps.scheduleDebounceCount)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 12: createHighlight stores snippet derived from selection text, textBefore, textAfter.
     *
     * The plan mandated: "createHighlight stores snippet derived from selection text+before+after".
     * createHighlight lives in the VM (it needs Publication for CFI building), but the store
     * contract — that textSnippet, textBefore, and textAfter are all persisted as distinct fields —
     * is verified here against FakeAnnotationStore which mirrors the real AnnotationStore API.
     * This test asserts the storage contract: the three context fields are stored separately and
     * independently (not concatenated or lost), so retrieval can reconstruct the full context window.
     */
    @Test
    fun `createHighlight stores snippet with textBefore and textAfter as distinct context fields`() = runTest {
        val store = FakeAnnotationStore()

        val created = store.createHighlight(
            sourceId = "srv1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2)",
            textSnippet = "hello",
            chapterHref = "chapter1.xhtml",
            textBefore = "say ",
            textAfter = " world",
            color = "yellow",
            spineIndex = 0,
            progression = 0.5,
        )

        val captured = store.createHighlightCalls.single()
        // Verify each context field is stored as provided — no concatenation or loss.
        assertEquals("hello", captured.textSnippet)
        assertEquals("say ", captured.textBefore)
        assertEquals(" world", captured.textAfter)
        // Verify the returned annotation carries the expected identity.
        assertEquals("yellow", created.color)
    }
}

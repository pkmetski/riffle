package com.riffle.app.feature.reader.session

import android.net.FakeUri
import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.HighlightColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class FakeAnnotationStore : AnnotationStore {
        val highlights = MutableStateFlow<List<Annotation>>(emptyList())
        val allAnnotations = MutableStateFlow<List<Annotation>>(emptyList())

        val deletedIds = mutableListOf<String>()
        val recoloredIds = mutableListOf<Pair<String, String>>()
        val updatedNotes = mutableListOf<Pair<String, String?>>()

        override fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>> = highlights
        override fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>> = MutableStateFlow(emptyList())
        override fun observeAnnotations(serverId: String, itemId: String): Flow<List<Annotation>> = allAnnotations
        override fun observeAnnotationsForServer(serverId: String): Flow<List<Annotation>> = MutableStateFlow(emptyList())
        override suspend fun createHighlight(
            serverId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, textBefore: String, textAfter: String, color: String,
            spineIndex: Int, progression: Double,
        ): Annotation = makeAnnotation(id = "h1", type = "highlight", cfi = cfi, color = color)
        override suspend fun createBookmark(
            serverId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String,
        ): Annotation = makeAnnotation(id = "b1", type = "bookmark", cfi = cfi)
        override suspend fun delete(id: String) { deletedIds.add(id) }
        override suspend fun recolor(id: String, color: String) { recoloredIds.add(id to color) }
        override suspend fun updateNote(id: String, note: String?) { updatedNotes.add(id to note) }
        override suspend fun renameBookmark(id: String, title: String) {}
        override suspend fun findByItemAndCfi(serverId: String, itemId: String, cfi: String): Annotation? = null
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
        type: String = "highlight",
        cfi: String = "epubcfi(/6/4!/4/2)",
        color: String = "yellow",
    ) = makeAnnotation(id, type, cfi, color)

    companion object {
        fun makeAnnotation(
            id: String = "a1",
            type: String = "highlight",
            cfi: String = "epubcfi(/6/4!/4/2)",
            color: String = "yellow",
        ) = Annotation(
        id = id,
        serverId = "srv1",
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

    private fun makeSession(
        store: FakeAnnotationStore = FakeAnnotationStore(),
        syncOps: FakeSyncOps,
        scope: CoroutineScope,
        statusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
        flushScope: ProgressFlushScope = ProgressFlushScope(
            CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        ),
    ) = AnnotationSession(
        scope = scope,
        annotationStore = store,
        annotationStatusStore = statusStore,
        progressFlushScope = flushScope,
        startLiveSync = syncOps.makeStartLiveSync(scope),
        scheduleSync = syncOps.makeScheduleDebounce(),
        syncOnOpen = syncOps.makeSyncOnOpen(),
        syncOnClose = syncOps.makeSyncOnClose(),
    )

    private fun defaultBind(
        session: AnnotationSession,
        store: FakeAnnotationStore = FakeAnnotationStore(),
        highlightRenderResolver: suspend (Annotation) -> EpubReaderViewModel.HighlightRender? = { null },
        cfiLocatorResolver: suspend (String) -> Locator? = { null },
    ) {
        session.bind(
            serverId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            currentLocator = MutableStateFlow(null),
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
            serverId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            currentLocator = MutableStateFlow(null),
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
            serverId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            currentLocator = MutableStateFlow(null),
            highlightRenderResolver = { null },
            cfiLocatorResolver = { cfi -> if (cfi == anno.cfi) targetLocator else null },
        )

        session.openAnnotationsPanel()
        assertTrue(session.annotationsPanelVisible.value)

        // Collect the channel event
        val received = mutableListOf<Locator>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation("a1")

        assertEquals(1, received.size)
        assertEquals(targetLocator, received[0])
        assertFalse(session.annotationsPanelVisible.value)

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
            CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
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
            serverId = "srv1",
            namespace = "ns1",
            itemId = "item2",
            currentLocator = MutableStateFlow(null),
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
}

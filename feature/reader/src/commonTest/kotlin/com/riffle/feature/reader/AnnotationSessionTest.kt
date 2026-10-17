package com.riffle.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.core.sync.AnnotationSyncStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSessionTest {

    // ------ Test infrastructure -----------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    private class TestApplicationScope(
        private val scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    ) : ApplicationScope {
        override val coroutineScope: CoroutineScope = scope
        override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
            scope.launch(block = block)
        override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
            scope.async(block = block).await()
    }

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
        val originFontFamily: String,
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
            embeddedFigures: List<EmbeddedFigure>?,
            originFontFamily: String,
            textSnippetHtml: String?,
        ): Annotation {
            createHighlightCalls.add(
                CreateHighlightArgs(sourceId, itemId, cfi, textSnippet, chapterHref, textBefore, textAfter, color, spineIndex, progression, originFontFamily)
            )
            return makeAnnotation(id = "h1", type = "highlight", cfi = cfi, color = color)
        }
        override suspend fun createBookmark(
            sourceId: String, itemId: String, cfi: String, textSnippet: String,
            chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String,
            originFontFamily: String, fragmentAnchor: String?,
        ): Annotation = makeAnnotation(id = "b1", type = "bookmark", cfi = cfi)
        override suspend fun createImageAnnotation(
            sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String,
            spineIndex: Int, progression: Double, imageHref: String?, imageSvg: String?, imageBytes: String?, color: String,
        ): Annotation = makeAnnotation(id = "img1", type = "image", cfi = cfi, color = color)
        override suspend fun upgradeImageToCaptionHighlight(
            id: String, cfi: String, textSnippet: String, textBefore: String, textAfter: String,
            figure: EmbeddedFigure,
        ): Annotation? = null
        override suspend fun mergeFiguresIntoHighlight(
            id: String, newFigures: List<EmbeddedFigure>,
        ): Annotation? = null
        override suspend fun delete(id: String) { deletedIds.add(id) }
        override suspend fun recolor(id: String, color: String) { recoloredIds.add(id to color) }
        override suspend fun updateNote(id: String, note: String?) { updatedNotes.add(id to note) }
        override suspend fun renameBookmark(id: String, title: String) {}
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
        override suspend fun findImageAnnotationForFigure(
            sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?,
        ): Annotation? = null
        override suspend fun backfillNullOriginFontFamily(
            sourceId: String, itemId: String, fontFamily: String,
        ): Int = 0
        override suspend fun healSentinelOriginFontFamily(
            sourceId: String, itemId: String, sentinel: String, fontFamily: String,
        ): Int = 0
    }

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
    }

    private fun buildLocator(
        href: String = "chapter1.xhtml",
        progression: Double = 0.3,
        fragments: List<String> = emptyList(),
    ): String {
        val fragmentsStr = if (fragments.isNotEmpty()) {
            ""","fragments":[${fragments.joinToString(",") { "\"$it\"" }}]"""
        } else ""
        return """{"href":"$href","type":"application/xhtml+xml","locations":{"progression":$progression$fragmentsStr}}"""
    }

    private data class ParsedLocations(val progression: Double?, val fragments: List<String>?)
    private data class ParsedText(val highlight: String?, val before: String?, val after: String?)
    private data class ParsedLocator(val href: String?, val mediaType: String?, val locations: ParsedLocations?, val text: ParsedText?)

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*)" """.trim())
        return pattern.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractJsonDouble(json: String, key: String): Double? {
        val pattern = Regex(""""$key"\s*:\s*([0-9.eE+\-]+)""")
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractJsonArray(json: String, key: String): List<String>? {
        val pattern = Regex(""""$key"\s*:\s*\[([^\]]*)]""")
        val content = pattern.find(json)?.groupValues?.get(1) ?: return null
        return Regex(""""([^"]*)"""").findAll(content).map { it.groupValues[1] }.toList()
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val keyIdx = json.indexOf(""""$key"""")
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx)
        val objStart = json.indexOf('{', colonIdx)
        if (objStart < 0) return null
        var depth = 0
        var i = objStart
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(objStart, i + 1) }
            }
            i++
        }
        return null
    }

    private fun parseLocator(json: String): ParsedLocator? = runCatching {
        val locObj = extractJsonObject(json, "locations")
        val textObj = extractJsonObject(json, "text")
        ParsedLocator(
            href = extractJsonString(json, "href"),
            mediaType = extractJsonString(json, "type"),
            locations = locObj?.let {
                ParsedLocations(
                    progression = extractJsonDouble(it, "progression"),
                    fragments = extractJsonArray(it, "fragments"),
                )
            },
            text = textObj?.let {
                ParsedText(
                    highlight = extractJsonString(it, "highlight"),
                    before = extractJsonString(it, "before"),
                    after = extractJsonString(it, "after"),
                )
            },
        )
    }.getOrNull()

    private class FakeHighlightColorPreferencesStore(
        initial: HighlightColor = HighlightColor.DEFAULT,
    ) : HighlightColorPreferencesStore {
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

    private class FakeEmphasisPreferencesStore : EmphasisPreferencesStore {
        private val perBook = mutableMapOf<String, MutableStateFlow<Set<EmphasisStyle>>>()
        private fun k(s: String, i: String) = "$s:$i"
        private fun flowFor(s: String, i: String) =
            perBook.getOrPut(k(s, i)) { MutableStateFlow(emptySet()) }
        override fun lastUsedStyles(sourceId: String, itemId: String): Flow<Set<EmphasisStyle>> =
            flowFor(sourceId, itemId)
        override suspend fun setLastUsedStyles(sourceId: String, itemId: String, value: Set<EmphasisStyle>) {
            flowFor(sourceId, itemId).value = value
        }
    }

    private fun makeSession(
        store: FakeAnnotationStore = FakeAnnotationStore(),
        syncOps: FakeSyncOps,
        scope: CoroutineScope,
        statusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
        colorPrefsStore: HighlightColorPreferencesStore = FakeHighlightColorPreferencesStore(),
        emphasisPrefsStore: EmphasisPreferencesStore = FakeEmphasisPreferencesStore(),
        flushScope: ProgressFlushScope = ProgressFlushScope(
            TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()))
        ),
        mergeAfterEdit: suspend (String, String, String?) -> Unit = { _, _, _ -> },
        commitDraftWithNote: suspend (String?) -> Unit = { _ -> },
    ) = AnnotationSession(
        scope = scope,
        annotationStore = store,
        annotationStatusStore = statusStore,
        highlightColorPreferencesStore = colorPrefsStore,
        emphasisPreferencesStore = emphasisPrefsStore,
        progressFlushScope = flushScope,
        startLiveSync = syncOps.makeStartLiveSync(scope),
        scheduleSync = syncOps.makeScheduleDebounce(),
        syncOnOpen = syncOps.makeSyncOnOpen(),
        syncOnClose = syncOps.makeSyncOnClose(),
        mergeAfterEdit = mergeAfterEdit,
        commitDraftWithNote = commitDraftWithNote,
    )

    private fun defaultBind(
        session: AnnotationSession,
        store: FakeAnnotationStore = FakeAnnotationStore(),
        highlightRenderResolver: suspend (Annotation) -> List<HighlightRender> = { emptyList() },
        cfiLocatorResolver: suspend (String) -> String? = { null },
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

    @Test
    fun highlightRendersReactivelyReflectsAnnotationStoreObserveHighlights() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)
        val anno = fakeAnnotation()
        val render = HighlightRender(anno.id, buildLocator(), anno.color, anno.note)

        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { a -> if (a.id == anno.id) listOf(render) else emptyList() },
            cfiLocatorResolver = { null },
        )

        assertEquals(emptyList<HighlightRender>(), session.highlightRenders.value)

        store.allAnnotations.value = listOf(anno)
        assertEquals(1, session.highlightRenders.value.size)
        assertEquals(anno.id, session.highlightRenders.value[0].id)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun highlightRendersAreStableWhenTheSameAnnotationsAreReEmitted() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        val annotations = listOf(
            fakeAnnotation(id = "a1", color = "yellow"),
            fakeAnnotation(id = "a2", color = "green"),
            fakeAnnotation(id = "a3", color = "blue"),
        )
        val locatorJson = buildLocator()
        val resolver: suspend (Annotation) -> List<HighlightRender> = { a ->
            listOf(HighlightRender(a.id, locatorJson, a.color, a.note))
        }
        session.bind(
            sourceId = "srv1", namespace = "ns1", itemId = "item1",
            highlightRenderResolver = resolver, cfiLocatorResolver = { null },
        )

        store.allAnnotations.value = annotations
        val firstIds    = session.highlightRenders.value.map { it.id }
        val firstColors = session.highlightRenders.value.map { it.color }
        assertEquals(3, firstIds.size)

        store.allAnnotations.value = annotations.toList()
        assertEquals(firstIds.size, session.highlightRenders.value.size, "re-emission must not change render count")
        assertEquals(firstIds, session.highlightRenders.value.map { it.id }, "render ids must be stable across re-emission")
        assertEquals(firstColors, session.highlightRenders.value.map { it.color }, "render colors must be stable across re-emission")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun recolorHighlightUpdatesStoreAndSchedulesDebounceSync() = runTest {
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

    @Test
    fun recolorHighlightPersistsColourAsLastUsedForTheBoundBook() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val colorPrefs = FakeHighlightColorPreferencesStore(initial = HighlightColor.YELLOW)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope, colorPrefsStore = colorPrefs)

        defaultBind(session)

        session.recolorHighlight("h1", HighlightColor.GREEN)

        assertEquals(HighlightColor.GREEN, colorPrefs.currentValue(sourceId = "srv1", itemId = "item1"))
        assertEquals(HighlightColor.YELLOW, colorPrefs.currentValue(sourceId = "srv1", itemId = "item2"))

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun lastUsedHighlightColorStateFlowReflectsTheBoundBooksStoredValue() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val colorPrefs = FakeHighlightColorPreferencesStore(initial = HighlightColor.DEFAULT)
        colorPrefs.setLastUsedColor("srv1", "item1", HighlightColor.BLUE)
        val session = makeSession(syncOps = FakeSyncOps(), scope = sessionScope, colorPrefsStore = colorPrefs)

        defaultBind(session)

        assertEquals(HighlightColor.BLUE, session.lastUsedHighlightColor.value)

        colorPrefs.setLastUsedColor("srv1", "item1", HighlightColor.GREEN)
        assertEquals(HighlightColor.GREEN, session.lastUsedHighlightColor.value)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun deleteAnnotationCascadesSiblingEmphasisRowsAtTheSameCfi() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        defaultBind(session)

        val cfi = "epubcfi(/6/4!/4/2/1:0,/1:10)"
        val highlight = fakeAnnotation(id = "hFmt", cfi = cfi).copy(color = "", note = null)
        val emphasis = fakeAnnotation(id = "eBold", cfi = cfi)
            .copy(type = AnnotationEntity.TYPE_EMPHASIS, color = "")
        val unrelated = fakeAnnotation(id = "eOther", cfi = "epubcfi(/6/8!/4/2/1:5)")
            .copy(type = AnnotationEntity.TYPE_EMPHASIS, color = "")
        store.allAnnotations.value = listOf(highlight, emphasis, unrelated)

        session.deleteAnnotation("hFmt")

        assertTrue("eBold" in store.deletedIds, "sibling emphasis at the same CFI must be deleted")
        assertTrue("hFmt" in store.deletedIds, "the highlight itself must be deleted")
        assertTrue("eOther" !in store.deletedIds, "unrelated emphasis at a different CFI must survive")
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dismissHighlightActionsTombstonesEmptyJustCreatedFromDraftHighlight() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        defaultBind(session)

        val empty = fakeAnnotation(id = "hEmpty").copy(color = "", note = null)
        store.allAnnotations.value = listOf(empty)
        session.openHighlightActions(
            "hEmpty",
            AnchorRect.Zero,
            justCreatedFromDraft = true,
        )

        session.dismissHighlightActions()

        assertEquals(listOf("hEmpty"), store.deletedIds)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dismissHighlightActionsPreservesExistingEmptyHighlightOpenedByTap() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        defaultBind(session)

        val empty = fakeAnnotation(id = "hExisting").copy(color = "", note = null)
        store.allAnnotations.value = listOf(empty)
        session.openHighlightActions("hExisting", AnchorRect.Zero)

        session.dismissHighlightActions()

        assertTrue(
            store.deletedIds.isEmpty(),
            "existing annotation opened by user tap must survive dismiss even when empty",
        )
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dismissHighlightActionsKeepsJustCreatedHighlightWhenASiblingEmphasisExists() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        defaultBind(session)

        val emptyHighlight = fakeAnnotation(id = "hFmt", cfi = "epubcfi(/6/4!/4/2/1:0)").copy(color = "", note = null)
        val emphasis = fakeAnnotation(id = "eBold", cfi = "epubcfi(/6/4!/4/2/1:0)")
            .copy(type = AnnotationEntity.TYPE_EMPHASIS, color = "")
        store.allAnnotations.value = listOf(emptyHighlight, emphasis)
        session.openHighlightActions("hFmt", AnchorRect.Zero, justCreatedFromDraft = true)

        session.dismissHighlightActions()

        assertTrue(store.deletedIds.isEmpty(), "format-only highlight must survive dismiss when emphasis carries the payload")
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun annotationsFlowIncludesFormatOnlyHighlightAnchorsForVmLookups() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        defaultBind(session)

        val formatOnly = fakeAnnotation(id = "hFmt").copy(color = "", note = null)
        val realHighlight = fakeAnnotation(id = "hReal").copy(color = "yellow", note = null)
        store.allAnnotations.value = listOf(formatOnly, realHighlight)

        val ids = session.annotations.value.map { it.id }.toSet()
        assertEquals(setOf("hFmt", "hReal"), ids)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun lastUsedHighlightColorSwapsToTheNewBookOnRebind() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val colorPrefs = FakeHighlightColorPreferencesStore(initial = HighlightColor.DEFAULT)
        colorPrefs.setLastUsedColor("srv1", "item1", HighlightColor.BLUE)
        colorPrefs.setLastUsedColor("srv1", "item2", HighlightColor.RED)
        val session = makeSession(syncOps = FakeSyncOps(), scope = sessionScope, colorPrefsStore = colorPrefs)

        defaultBind(session)
        assertEquals(HighlightColor.BLUE, session.lastUsedHighlightColor.value)

        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item2",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { null },
        )
        assertEquals(HighlightColor.RED, session.lastUsedHighlightColor.value)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun individualEditsDoNotFireMergeAfterEdit() = runTest {
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

    @Test
    fun dismissHighlightActionsFiresMergeAfterEditWithTheCurrentRowState() = runTest {
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
        session.openHighlightActions("h1", AnchorRect.Zero)

        session.dismissHighlightActions()

        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("green", mergeCalls[0].second)
        assertNull(mergeCalls[0].third)
        assertNull(session.highlightToEdit.value)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dismissHighlightActionsAwaitsTheAnnotationsFlowWhenTheRowIsNotYetPresent() = runTest {
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
        store.allAnnotations.value = emptyList()
        session.openHighlightActions("h1", AnchorRect.Zero)

        session.dismissHighlightActions()

        assertEquals(0, mergeCalls.size)

        store.allAnnotations.value = listOf(makeAnnotation(id = "h1", color = "green").copy(note = null))

        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("green", mergeCalls[0].second)
        assertNull(mergeCalls[0].third)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun openNoteEditorClosesActionsPopupWithoutFiringMerge() = runTest {
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
        session.openHighlightActions("h1", AnchorRect.Zero)

        session.openNoteEditor("h1", AnchorRect.Zero)

        assertNull(session.highlightToEdit.value)
        assertEquals("h1", session.noteEditorTarget.value?.id)
        assertEquals(0, mergeCalls.size)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dismissHighlightActionsIsANoOpWhenNoteEditorIsOpen() = runTest {
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
        session.openHighlightActions("h1", AnchorRect.Zero)
        session.openNoteEditor("h1", AnchorRect.Zero)

        session.dismissHighlightActions()

        assertEquals(0, mergeCalls.size)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun commitNoteEditFiresMergeAfterEditWithTheNewNote() = runTest {
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
        session.openNoteEditor("h1", AnchorRect.Zero)

        session.commitNoteEdit("h1", "my thought")

        assertEquals(listOf<Pair<String, String?>>("h1" to "my thought"), store.updatedNotes)
        assertNull(session.noteEditorTarget.value)
        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("blue", mergeCalls[0].second)
        assertEquals("my thought", mergeCalls[0].third)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun cancelNoteEditFiresMergeAfterEditWithUnchangedRowState() = runTest {
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
        session.openNoteEditor("h1", AnchorRect.Zero)

        session.cancelNoteEdit()

        assertNull(session.noteEditorTarget.value)
        assertEquals(1, mergeCalls.size)
        assertEquals("h1", mergeCalls[0].first)
        assertEquals("green", mergeCalls[0].second)
        assertNull(mergeCalls[0].third)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun commitNoteEditOnADraftRoutesToTheDraftCommitCallbackInsteadOfUpdateNote() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val draftNoteCalls = mutableListOf<String?>()
        val session = makeSession(
            store = store, syncOps = FakeSyncOps(), scope = sessionScope,
            commitDraftWithNote = { note -> draftNoteCalls += note },
        )
        defaultBind(session)
        session.openHighlightActions(AnnotationSession.DRAFT_ANNOTATION_ID, AnchorRect.Zero)
        session.openNoteEditor(AnnotationSession.DRAFT_ANNOTATION_ID, AnchorRect.Zero)

        session.commitNoteEdit(AnnotationSession.DRAFT_ANNOTATION_ID, "my thought")

        assertEquals(listOf<String?>("my thought"), draftNoteCalls)
        assertEquals(emptyList<Pair<String, String?>>(), store.updatedNotes)
        assertNull(session.noteEditorTarget.value)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun commitNoteEditOnADraftWithBlankNoteRoutesNullToTheCallback() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val draftNoteCalls = mutableListOf<String?>()
        val session = makeSession(
            store = store, syncOps = FakeSyncOps(), scope = sessionScope,
            commitDraftWithNote = { note -> draftNoteCalls += note },
        )
        defaultBind(session)
        session.openNoteEditor(AnnotationSession.DRAFT_ANNOTATION_ID, AnchorRect.Zero)

        session.commitNoteEdit(AnnotationSession.DRAFT_ANNOTATION_ID, "   ")

        assertEquals(listOf<String?>(null), draftNoteCalls)
        assertEquals(emptyList<Pair<String, String?>>(), store.updatedNotes)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun cancelNoteEditOnADraftRoutesNullToTheDraftCommitCallback() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val draftNoteCalls = mutableListOf<String?>()
        val session = makeSession(
            store = FakeAnnotationStore(), syncOps = FakeSyncOps(), scope = sessionScope,
            commitDraftWithNote = { note -> draftNoteCalls += note },
        )
        defaultBind(session)
        session.openNoteEditor(AnnotationSession.DRAFT_ANNOTATION_ID, AnchorRect.Zero)

        session.cancelNoteEdit()

        assertEquals(listOf<String?>(null), draftNoteCalls)
        assertNull(session.noteEditorTarget.value)
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dismissHighlightActionsWithNoTargetDoesNotFireMerge() = runTest {
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

    @Test
    fun forceCloseHighlightEditTargetClearsHighlightToEditSynchronouslyBeforeCoroutinesRun() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val session = makeSession(syncOps = FakeSyncOps(), scope = sessionScope)
        defaultBind(session)
        runCurrent()

        session.openHighlightActions(AnnotationSession.DRAFT_ANNOTATION_ID, AnchorRect(0, 0, 100, 50))
        runCurrent()
        assertNotNull(session.highlightToEdit.value)

        session.forceCloseHighlightEditTarget()

        assertNull(session.highlightToEdit.value)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun deleteHighlightRemovesFromStoreAndSchedulesDebounceSync() = runTest {
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

    @Test
    fun openAnnotationsPanelAndNavigateToAnnotationEmitsViaChannel() = runTest {
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
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { cfi -> if (cfi == anno.cfi) targetLocator else null },
        )

        session.openAnnotationsPanel()
        assertTrue(session.annotationsPanelVisible.value)

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation("a1")

        assertEquals(1, received.size)
        val receivedLocator = parseLocator(received[0].locatorJson)
        val expectedLocator = parseLocator(targetLocator)
        assertEquals(expectedLocator?.href, receivedLocator?.href)
        assertEquals(expectedLocator?.mediaType, receivedLocator?.mediaType)
        assertEquals(expectedLocator?.locations, receivedLocator?.locations)
        assertFalse(received[0].isBookmark)
        assertEquals(anno.textSnippet, received[0].annotation?.textSnippet)
        assertEquals(anno.textBefore, received[0].annotation?.textBefore)
        assertEquals(anno.textAfter, received[0].annotation?.textAfter)
        assertEquals("a1", received[0].annotationId)
        assertFalse(session.annotationsPanelVisible.value)

        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationTagsBookmarksAsIsBookmarkTrueSoDownstreamCanBranchOnType() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val targetLocator = buildLocator(progression = 0.71)
        val anno = fakeAnnotation(id = "b1", type = AnnotationEntity.TYPE_BOOKMARK, cfi = "epubcfi(/6/4!/4/2)")
        store.allAnnotations.value = listOf(anno)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { _ -> targetLocator },
        )

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }
        session.navigateToAnnotation("b1")

        assertEquals(1, received.size)
        assertTrue(received[0].isBookmark)
        val receivedBookmarkLocator = parseLocator(received[0].locatorJson)
        val expectedBookmarkLocator = parseLocator(targetLocator)
        assertTrue(
            kotlin.math.abs(
                (expectedBookmarkLocator?.locations?.progression ?: 0.0) -
                    (receivedBookmarkLocator?.locations?.progression ?: 0.0)
            ) < 0.000001,
            "bookmark navigation emits the resolver's locatorJson; enrichment is the host's job",
        )
        assertEquals(expectedBookmarkLocator?.href, receivedBookmarkLocator?.href)
        assertEquals(expectedBookmarkLocator?.mediaType, receivedBookmarkLocator?.mediaType)
        assertNull(receivedBookmarkLocator?.text?.highlight, "bookmarks are point locators and must not receive a highlight TextQuote")

        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationRetainsCfiProgressionForLegacyBookmarkWithUnknownZeroProgression() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val targetLocator = buildLocator(progression = 0.71)
        val legacyBookmark = fakeAnnotation(
            id = "legacy-bookmark",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
        ).copy(progression = 0.0)
        store.allAnnotations.value = listOf(legacyBookmark)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { targetLocator },
        )
        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation(legacyBookmark.id)

        assertTrue(
            kotlin.math.abs(
                (parseLocator(targetLocator)?.locations?.progression ?: 0.0) -
                    (parseLocator(received.single().locatorJson)?.locations?.progression ?: 0.0)
            ) < 0.000001,
            "a legacy zero means unknown when its translated CFI points later in the chapter",
        )
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationSetsLocationsFragmentsFromFragmentAnchorOnNewBookmarks() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val targetLocator = buildLocator(progression = 0.43)
        val bookmark = fakeAnnotation(
            id = "bm-new",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
        ).copy(progression = 0.43, fragmentAnchor = "para-ch03")
        store.allAnnotations.value = listOf(bookmark)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { targetLocator },
        )
        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation(bookmark.id)

        assertEquals(1, received.size)
        assertTrue(received[0].isBookmark)
        assertEquals(
            listOf("para-ch03"),
            parseLocator(received[0].locatorJson)?.locations?.fragments,
            "bookmark with fragmentAnchor must populate locations.fragments for element-anchored snap",
        )
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationPreservesZeroProgressionForNewStyleBookmarkOnFirstColumn() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val cfiFallbackLocator = buildLocator(progression = 0.71)
        val bookmark = fakeAnnotation(
            id = "bm-first-col",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
        ).copy(progression = 0.0, fragmentAnchor = "para-intro")
        store.allAnnotations.value = listOf(bookmark)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)
        session.bind(
            sourceId = "srv1", namespace = "ns1", itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { cfiFallbackLocator },
        )
        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation(bookmark.id)

        assertEquals(1, received.size)
        assertTrue(
            kotlin.math.abs((parseLocator(received[0].locatorJson)?.locations?.progression ?: -1.0) - 0.0) < 0.000001,
            "new-style bookmark at progression=0.0 must NOT fall back to the CFI progression (0.71)",
        )
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationRetainsProgressionPathForLegacyBookmarksWithoutFragmentAnchor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val locatorWithContainerFragmentJson = buildLocator(
            href = "chapter1.xhtml",
            progression = 0.43,
            fragments = listOf("section-ch01"),
        )
        val legacyBookmark = fakeAnnotation(
            id = "bm-legacy",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
        ).copy(progression = 0.43, fragmentAnchor = null)
        store.allAnnotations.value = listOf(legacyBookmark)
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)
        session.bind(
            sourceId = "srv1", namespace = "ns1", itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { locatorWithContainerFragmentJson },
        )
        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }

        session.navigateToAnnotation(legacyBookmark.id)

        val receivedLegacyLocator = parseLocator(received[0].locatorJson)
        assertEquals(1, received.size)
        assertTrue(received[0].isBookmark)
        assertTrue(
            receivedLegacyLocator?.locations?.fragments?.isEmpty() == true,
            "legacy bookmark must clear CFI-derived container fragments so snapAfterGoTo uses progression fallback instead of always snapping to column 0",
        )
        assertTrue(
            kotlin.math.abs((receivedLegacyLocator?.locations?.progression ?: -1.0) - legacyBookmark.progression) < 0.000001,
        )
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationSetsImageSrcFromImageHrefOnTypeImageAnnotations() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val targetLocator = buildLocator()
        val imageAnnotation = Annotation(
            id = "img1",
            sourceId = "srv1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "yellow",
            note = null,
            textSnippet = "Figure 1.1",
            textBefore = "",
            textAfter = "",
            chapterHref = "chapter1.xhtml",
            spineIndex = 0,
            progression = 0.4,
            bookmarkTitle = "",
            createdAt = 1000L,
            updatedAt = 1001L,
            imageHref = "EPUB/images/figure-1.png",
        )
        store.allAnnotations.value = listOf(imageAnnotation)
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { _ -> targetLocator },
        )

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }
        session.navigateToAnnotation("img1")

        assertEquals(1, received.size)
        assertEquals(
            "EPUB/images/figure-1.png",
            received[0].imageSrc,
            "TYPE_IMAGE navigation must carry the imageHref as imageSrc so continuous mode can fall back to img[src]",
        )
        assertFalse(received[0].isBookmark)
        assertEquals("img1", received[0].annotationId)
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationEmitsNullImageSrcForTypeHighlightAnnotations() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val targetLocator = buildLocator()
        val highlight = fakeAnnotation(id = "h1", type = AnnotationEntity.TYPE_HIGHLIGHT)
        store.allAnnotations.value = listOf(highlight)
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { _ -> targetLocator },
        )

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }
        session.navigateToAnnotation("h1")

        assertEquals(1, received.size)
        assertNull(received[0].imageSrc, "TYPE_HIGHLIGHT navigation must NOT carry imageSrc")
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationEmitsNullImageSrcForTypeImageAnnotationsWithoutImageHrefAndNoCfiFragment() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val targetLocator = buildLocator()
        val imageAnnotationNoHref = Annotation(
            id = "img2",
            sourceId = "srv1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "yellow",
            note = null,
            textSnippet = "",
            textBefore = "",
            textAfter = "",
            chapterHref = "chapter1.xhtml",
            spineIndex = 0,
            progression = 0.5,
            bookmarkTitle = "",
            createdAt = 1000L,
            updatedAt = 1001L,
            imageHref = null,
        )
        store.allAnnotations.value = listOf(imageAnnotationNoHref)
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { _ -> targetLocator },
        )

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }
        session.navigateToAnnotation("img2")

        assertEquals(1, received.size)
        assertNull(received[0].imageSrc, "TYPE_IMAGE with no imageHref and no CFI fragment must emit null imageSrc")
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun navigateToAnnotationUsesCfiFragmentAsHashPrefixedImageSrcForPreMigration46TypeImageAnnotations() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val targetLocator = buildLocator(fragments = listOf("fig_001_09fig01"))
        val oldImageAnnotation = Annotation(
            id = "img3",
            sourceId = "srv1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = "epubcfi(/6/4!/4/2/6[fig_001_09fig01])",
            color = "yellow",
            note = null,
            textSnippet = "Figure 1",
            textBefore = "",
            textAfter = "",
            chapterHref = "chapter1.xhtml",
            spineIndex = 0,
            progression = 0.3,
            bookmarkTitle = "",
            createdAt = 1000L,
            updatedAt = 1001L,
            imageHref = null,
        )
        store.allAnnotations.value = listOf(oldImageAnnotation)
        val session = makeSession(store = store, syncOps = FakeSyncOps(), scope = sessionScope)
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { _ -> targetLocator },
        )

        val received = mutableListOf<AnnotationSession.AnnotationNavigationEvent>()
        val collectJob = sessionScope.launch {
            session.annotationNavigationEvents.collect { received.add(it) }
        }
        session.navigateToAnnotation("img3")

        assertEquals(1, received.size)
        assertEquals(
            "#fig_001_09fig01",
            received[0].imageSrc,
            "Pre-migration-46 TYPE_IMAGE annotations must use CFI element id as hash-prefixed imageSrc",
        )
        collectJob.cancel()
        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun updateHighlightNotePersistsNoteAndSchedulesDebounceSync() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        session.updateHighlightNote("h1", "My note")

        assertEquals(1, syncOps.scheduleDebounceCount)
        assertEquals(listOf<Pair<String, String?>>("h1" to "My note"), store.updatedNotes)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun deleteAnnotationRemovesFromStoreAndClearsHighlightToEditIfNeeded() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        session.openHighlightActions("h1", AnchorRect.Zero)
        assertEquals("h1", session.highlightToEdit.value?.id)

        session.deleteAnnotation("h1")

        assertTrue(store.deletedIds.contains("h1"))
        assertNull(session.highlightToEdit.value, "highlightToEdit should be cleared when its annotation is deleted")
        assertEquals(1, syncOps.scheduleDebounceCount)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun deleteHighlightClearsHighlightToEditEvenWhenTheNamespaceNeverResolved() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        session.bind(
            sourceId = "srv1",
            namespace = "",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { null },
        )
        session.openHighlightActions("h1", AnchorRect.Zero)
        assertEquals("h1", session.highlightToEdit.value?.id)

        session.deleteHighlight("h1")

        assertTrue(store.deletedIds.contains("h1"))
        assertNull(session.highlightToEdit.value, "A deleted highlight must not stay the edit target just because the book does not sync")
        assertEquals(0, syncOps.scheduleDebounceCount, "An unresolved namespace must not schedule a push")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun deleteAnnotationClearsHighlightToEditEvenWhenTheNamespaceNeverResolved() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        session.bind(
            sourceId = "srv1",
            namespace = "",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { null },
        )
        session.openHighlightActions("h1", AnchorRect.Zero)
        assertEquals("h1", session.highlightToEdit.value?.id)

        session.deleteAnnotation("h1")

        assertTrue(store.deletedIds.contains("h1"))
        assertNull(session.highlightToEdit.value, "A deleted annotation must not stay the edit target just because the book does not sync")
        assertEquals(0, syncOps.scheduleDebounceCount, "An unresolved namespace must not schedule a push")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun createHighlightStoresSnippetWithTextBeforeAndTextAfterAsDistinctContextFields() = runTest {
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
            originFontFamily = "Georgia, serif",
        )

        val captured = store.createHighlightCalls.single()
        assertEquals("hello", captured.textSnippet)
        assertEquals("say ", captured.textBefore)
        assertEquals(" world", captured.textAfter)
        assertEquals("Georgia, serif", captured.originFontFamily)
        assertEquals("yellow", created.color)
    }

    @Test
    fun bindWithEmptyNamespaceStillStartsTheAnnotationFlowObserver() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val store = FakeAnnotationStore()
        val syncOps = FakeSyncOps()
        val session = makeSession(store = store, syncOps = syncOps, scope = sessionScope)

        val anno = fakeAnnotation()
        val render = HighlightRender(anno.id, buildLocator(), anno.color, anno.note)

        session.bind(
            sourceId = "srv1",
            namespace = "",
            itemId = "item1",
            highlightRenderResolver = { listOf(render) },
            cfiLocatorResolver = { null },
        )
        store.allAnnotations.value = listOf(anno)

        assertEquals(listOf(render), session.highlightRenders.value)
    }

    @Test
    fun bindDrivesTheSyncCoordinatorSoSyncOnOpenAndStartLiveSyncFire() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val session = makeSession(syncOps = syncOps, scope = sessionScope)

        defaultBind(session)

        assertTrue(syncOps.syncOnOpenCalled, "bind must drive the coordinator's syncOnOpen")
        assertTrue(syncOps.startLiveSyncCalled, "bind must drive the coordinator's startLiveSync")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun onBookClosedDelegatesToTheSyncCoordinator() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val session = makeSession(syncOps = syncOps, scope = sessionScope)

        defaultBind(session)
        val liveSyncJob = syncOps.lastLiveSyncJob

        session.onBookClosed()

        assertTrue(syncOps.syncOnCloseCalled, "onBookClosed must delegate the close-time push")
        assertTrue(liveSyncJob?.isCancelled == true, "onBookClosed must cancel the live-sync job")

        sessionScope.coroutineContext[Job]?.cancel()
    }
}

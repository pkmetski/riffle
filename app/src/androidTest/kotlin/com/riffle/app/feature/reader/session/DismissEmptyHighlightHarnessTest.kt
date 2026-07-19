package com.riffle.app.feature.reader.session

import androidx.compose.ui.unit.IntRect
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device regression harness for ADR 0046 §4's tombstone-on-dismiss gate: an EXISTING
 * empty highlight opened by tap must survive dismissal (the user un-bolded intending to keep
 * the anchor); a highlight the user JUST CREATED from a draft and left empty must tombstone
 * (the "picked ∅ with no formatting" phantom row).
 *
 * Uses a real in-memory Room DB and the real [com.riffle.core.data.AnnotationStoreImpl] so the
 * Room Flow → `AnnotationSession.bind` combine collect → `_annotations` / `_emphasisPool`
 * pipeline runs end-to-end on device, not against a StateFlow fake.
 */
@RunWith(AndroidJUnit4::class)
class DismissEmptyHighlightHarnessTest {

    private lateinit var db: RiffleDatabase
    private lateinit var store: AnnotationStoreImpl
    private lateinit var sessionScope: CoroutineScope
    private lateinit var session: AnnotationSession

    private class FixedDeviceIdStore : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-test"
    }

    private class FakeHighlightColorPreferencesStore : HighlightColorPreferencesStore {
        private val state = MutableStateFlow(HighlightColor.YELLOW)
        override fun lastUsedColor(sourceId: String, itemId: String): Flow<HighlightColor> = state
        override suspend fun setLastUsedColor(sourceId: String, itemId: String, value: HighlightColor) {
            state.value = value
        }
    }

    private class FakeEmphasisPreferencesStore : EmphasisPreferencesStore {
        private val state = MutableStateFlow<Set<EmphasisStyle>>(emptySet())
        override fun lastUsedStyles(sourceId: String, itemId: String): Flow<Set<EmphasisStyle>> = state
        override suspend fun setLastUsedStyles(sourceId: String, itemId: String, value: Set<EmphasisStyle>) {
            state.value = value
        }
    }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, RiffleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AnnotationStoreImpl(
            dao = db.annotationDao(),
            deviceIdStore = FixedDeviceIdStore(),
            clock = { 1_000L },
            idGenerator = { java.util.UUID.randomUUID().toString() },
        )
        val dispatcher = UnconfinedTestDispatcher()
        sessionScope = CoroutineScope(dispatcher + SupervisorJob())
        val appScope = object : ApplicationScope {
            private val inner = CoroutineScope(dispatcher + SupervisorJob())
            override val coroutineScope: CoroutineScope = inner
            override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
                inner.launch(block = block)
            override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
                kotlinx.coroutines.coroutineScope { block() }
        }
        val flushScope = ProgressFlushScope(appScope)
        session = AnnotationSession(
            scope = sessionScope,
            annotationStore = store,
            annotationStatusStore = AnnotationSyncStatusStore(),
            highlightColorPreferencesStore = FakeHighlightColorPreferencesStore(),
            emphasisPreferencesStore = FakeEmphasisPreferencesStore(),
            progressFlushScope = flushScope,
            startLiveSync = { _, _, _ -> Job() },
            scheduleSync = { _, _, _ -> },
            syncOnOpen = { _, _, _ -> },
            syncOnClose = { _, _, _ -> },
            mergeAfterEdit = { _, _, _ -> },
        )
    }

    @After
    fun tearDown() {
        sessionScope.coroutineContext[Job]?.cancel()
        db.close()
    }

    private suspend fun bind() {
        // FK-satisfying source row.
        db.sourceDao().upsert(
            SourceEntity(
                id = "srv1",
                url = "http://srv1",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "u",
            ),
        )
        session.bind(
            sourceId = "srv1",
            namespace = "ns1",
            itemId = "item1",
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { null },
        )
        // Yield so the combine collect observes the initial DB emission.
        yield()
    }

    private fun makeHighlight(
        id: String,
        cfi: String,
        color: String,
        note: String? = null,
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "srv1",
        itemId = "item1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = cfi,
        color = color,
        note = note,
        textSnippet = "hello",
        textBefore = "",
        textAfter = "",
        chapterHref = "ch1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 1L,
        updatedAt = 1L,
        originDeviceId = "device-test",
        lastModifiedByDeviceId = "device-test",
        deleted = false,
    )

    private fun makeEmphasis(id: String, cfi: String, styles: String): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "srv1",
        itemId = "item1",
        type = AnnotationEntity.TYPE_EMPHASIS,
        cfi = cfi,
        color = "",
        note = null,
        textSnippet = "hello",
        textBefore = "",
        textAfter = "",
        chapterHref = "ch1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 2L,
        updatedAt = 2L,
        originDeviceId = "device-test",
        lastModifiedByDeviceId = "device-test",
        deleted = false,
        emphasisStyles = styles,
    )

    private suspend fun awaitDao(predicate: (List<AnnotationEntity>) -> Boolean): List<AnnotationEntity> {
        return withTimeout(2_000) {
            db.annotationDao().observeForItem("srv1", "item1").first(predicate)
        }
    }

    /** Regression: an EXISTING empty highlight the user opened by TAP (not created from a draft)
     *  must survive dismissal even when the user just cleared its last emphasis chip. Reverting
     *  the `justCreatedFromDraft` gate in [AnnotationSession.dismissHighlightActions] flips this red. */
    @Test
    fun existingEmptyHighlight_opened_by_tap_survives_dismiss() = runBlocking {
        bind()
        val cfi = "epubcfi(/6/4!/4/2/1:0,/1:5)"
        db.annotationDao().upsert(makeHighlight(id = "hExisting", cfi = cfi, color = "", note = null))
        // Wait until the session's flow reflects the row.
        session.annotations.first { list -> list.any { it.id == "hExisting" } }

        // User taps existing annotation to open the sheet — plain overload, flag defaults to false.
        session.openHighlightActions("hExisting", IntRect(0, 0, 0, 0))
        session.dismissHighlightActions()
        // Yield so any launch-and-return coroutine kicked off by dismiss can run.
        yield(); yield()

        val row = db.annotationDao().getById("hExisting")
        assertNotNull("existing empty highlight opened by tap must survive dismiss", row)
        assertEquals(false, row!!.deleted)
    }

    /** Regression: a highlight the user JUST created from a draft and left empty (no colour,
     *  no note, no emphasis) is a phantom row and must be tombstoned on dismiss. */
    @Test
    fun emptyHighlight_just_created_from_draft_is_tombstoned_on_dismiss() = runBlocking {
        bind()
        val cfi = "epubcfi(/6/6!/4/2/1:0,/1:5)"
        db.annotationDao().upsert(makeHighlight(id = "hDraftEmpty", cfi = cfi, color = "", note = null))
        session.annotations.first { list -> list.any { it.id == "hDraftEmpty" } }

        // Simulate commitDraft's post-create sheet swap — the flag distinguishes this path.
        session.openHighlightActions("hDraftEmpty", IntRect(0, 0, 0, 0), justCreatedFromDraft = true)
        session.dismissHighlightActions()
        // Suspend until the tombstone lands (the delete is inside a launch that awaits the row).
        awaitDao { list -> list.none { it.id == "hDraftEmpty" } }

        val row = db.annotationDao().getById("hDraftEmpty")
        assertEquals(true, row?.deleted)
    }

    /** Regression: a just-created highlight with a sibling emphasis at its CFI is NOT empty —
     *  it's a legit "format-only" annotation and must survive even with the draft flag on. */
    @Test
    fun formatOnlyHighlight_with_sibling_emphasis_survives_dismiss_even_when_flagged_draft() = runBlocking {
        bind()
        val cfi = "epubcfi(/6/8!/4/2/1:0,/1:5)"
        db.annotationDao().upsert(makeHighlight(id = "hFmt", cfi = cfi, color = "", note = null))
        db.annotationDao().upsert(makeEmphasis(id = "eBold", cfi = cfi, styles = "bold"))
        session.emphasisPool.first { pool -> pool.any { it.cfi == cfi } }

        session.openHighlightActions("hFmt", IntRect(0, 0, 0, 0), justCreatedFromDraft = true)
        session.dismissHighlightActions()
        yield(); yield()

        val row = db.annotationDao().getById("hFmt")
        assertNotNull("format-only highlight with sibling emphasis must survive dismiss", row)
        assertEquals(false, row!!.deleted)
    }

    /** Regression: `deleteAnnotation` (invoked from the annotations panel) must cascade
     *  sibling emphasis rows at the same CFI. Without the cascade the DOM injector keeps
     *  the orphan emphasis and the text stays formatted — "delete doesn't refresh the book". */
    @Test
    fun deleteAnnotation_cascades_sibling_emphasis_at_same_cfi() = runBlocking {
        bind()
        val cfi = "epubcfi(/6/10!/4/2/1:0,/1:5)"
        val otherCfi = "epubcfi(/6/12!/4/2/1:0,/1:5)"
        db.annotationDao().upsert(makeHighlight(id = "hDel", cfi = cfi, color = "yellow"))
        db.annotationDao().upsert(makeEmphasis(id = "eSame", cfi = cfi, styles = "bold"))
        db.annotationDao().upsert(makeEmphasis(id = "eOther", cfi = otherCfi, styles = "italic"))
        // Wait until the session flow sees everything so `annotations`/`emphasisPool` are current.
        session.annotations.first { list -> list.any { it.id == "hDel" } }
        session.emphasisPool.first { pool -> pool.any { it.cfi == cfi } }

        session.deleteAnnotation("hDel")
        // Delete happens inline in deleteAnnotation; small yield to be safe with any launched sync.
        yield()

        assertEquals(true, db.annotationDao().getById("hDel")?.deleted)
        assertEquals(true, db.annotationDao().getById("eSame")?.deleted)
        assertEquals(false, db.annotationDao().getById("eOther")?.deleted)
    }
}

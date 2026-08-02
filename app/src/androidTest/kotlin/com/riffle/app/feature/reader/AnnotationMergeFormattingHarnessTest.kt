package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.SourceEntity
import com.riffle.core.database.openInMemoryRiffleDatabase
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * On-device regression for the 2026-07-24 recording: a plain green annotation partially
 * overlapped by a green + bold annotation must remain two edit targets. This exercises the
 * production merge decision against the bundled EPUB, then persists the expected result through
 * real Room + [AnnotationStoreImpl] to prove the bold sibling stays scoped to annotation B.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationMergeFormattingHarnessTest {

    private lateinit var database: RiffleDatabaseAccess
    private lateinit var store: AnnotationStoreImpl

    private class FixedDeviceIdStore : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-test"
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = openInMemoryRiffleDatabase(
            context = context,
            allowMainThreadQueries = true,
        )
        val nextId = AtomicInteger()
        store = AnnotationStoreImpl(
            dao = database.annotationDao(),
            deviceIdStore = FixedDeviceIdStore(),
            clock = { 1_000L + nextId.get() },
            idGenerator = { "annotation-${nextId.incrementAndGet()}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun plainGreenOverlappedByGreenBold_staysSeparateAndKeepsBoldOnNewRange() = runTest {
        database.sourceDao().upsert(
            SourceEntity(
                id = "source-1",
                url = "http://source-1",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "test",
            ),
        )
        val html = chapter1Html()
        val body = readableBodyText(html)
        val plainSnippet = "In the beginning there was text. The text was formless"
        val boldSnippet = "text was formless and the page was empty. Then came the reader"
        val plainStart = body.indexOf(plainSnippet).toLong()
        val boldStart = body.indexOf(boldSnippet).toLong()
        require(plainStart >= 0 && boldStart >= 0)
        val plainBefore = body.substring(0, plainStart.toInt()).takeLast(60)
        val boldBefore = body.substring(0, boldStart.toInt()).takeLast(60)
        val plainCfi = buildHighlightCfiRange(
            spineStep = 2,
            html = html,
            startChar = plainStart,
            endChar = plainStart + plainSnippet.length - 1L,
        ) ?: error("failed to build plain annotation CFI")
        val boldCfi = buildHighlightCfiRange(
            spineStep = 2,
            html = html,
            startChar = boldStart,
            endChar = boldStart + boldSnippet.length - 1L,
        ) ?: error("failed to build bold annotation CFI")
        val plainCandidate = annotation(
            cfi = plainCfi,
            textSnippet = plainSnippet,
            textBefore = plainBefore,
        )

        val merge = computeOverlapMerge(
            html = html,
            draftSnippet = boldSnippet,
            draftTextBefore = boldBefore,
            candidates = listOf(plainCandidate),
            draftEmphasisStyles = setOf(EmphasisStyle.BOLD),
            emphasisPool = emptyList(),
        )
        assertNull("plain green and overlapping green + bold must not merge", merge)

        store.createHighlight(
            sourceId = "source-1",
            itemId = "book-1",
            cfi = plainCfi,
            textSnippet = plainSnippet,
            chapterHref = "OEBPS/chapter1.xhtml",
            textBefore = plainBefore,
            color = "green",
            originFontFamily = "serif",
        )
        store.createHighlight(
            sourceId = "source-1",
            itemId = "book-1",
            cfi = boldCfi,
            textSnippet = boldSnippet,
            chapterHref = "OEBPS/chapter1.xhtml",
            textBefore = boldBefore,
            color = "green",
            originFontFamily = "serif",
        )
        store.createEmphasis(
            sourceId = "source-1",
            itemId = "book-1",
            cfi = boldCfi,
            textSnippet = boldSnippet,
            chapterHref = "OEBPS/chapter1.xhtml",
            textBefore = boldBefore,
            styles = setOf(EmphasisStyle.BOLD),
            originFontFamily = "serif",
        )

        val highlights = store.observeHighlights("source-1", "book-1").first()
        val emphasis = store.observeEmphasis("source-1", "book-1").first()
        assertEquals("both green edit targets must survive", 2, highlights.size)
        assertEquals(1, emphasis.size)
        assertEquals("bold must remain scoped to annotation B", boldCfi, emphasis.single().cfi)
        assertEquals(setOf(EmphasisStyle.BOLD), emphasis.single().emphasisStyles)
    }

    private fun annotation(
        cfi: String,
        textSnippet: String,
        textBefore: String,
    ) = Annotation(
        id = "plain-green",
        sourceId = "source-1",
        itemId = "book-1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = cfi,
        color = "green",
        note = null,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = "",
        chapterHref = "OEBPS/chapter1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun chapter1Html(): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        ZipInputStream(context.assets.open("test.epub")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("chapter1.xhtml")) {
                    return zip.readBytes().decodeToString()
                }
                entry = zip.nextEntry
            }
        }
        error("chapter1.xhtml not found in test.epub")
    }
}

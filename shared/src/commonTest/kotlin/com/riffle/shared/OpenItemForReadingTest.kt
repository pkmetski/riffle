package com.riffle.shared

import com.riffle.core.domain.DefaultApplicationScope
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1071 §17 — `RecordItemOpened` was bound on iOS and injected into `LibraryItemDetailViewModel`,
 * but `markOpened()` had zero iOS callers. Two things therefore never happened when a book was
 * opened on iPhone: the local `lastOpenedAt` bump that orders the In Progress row, and the
 * `touchOpenTimestamp` push that lets the user's other devices see the open via ABS's
 * `mediaProgress.lastUpdate`.
 *
 * iOS routes all four formats and both hosts (the per-library browser and the Riffle hub)
 * through one nav resolver, so [openItemForReading] is where the open is recorded. Deleting its
 * `launchSurvivable { recordItemOpened(item.id) }` line — the revert — turns `recorded` empty and
 * fails every assertion below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenItemForReadingTest {

    private fun item(
        id: String = "item-1",
        ebookFormat: EbookFormat = EbookFormat.Epub,
        hasAudio: Boolean = false,
    ) = LibraryItem(
        id = id,
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = ebookFormat,
        hasAudio = hasAudio,
    )

    @Test
    fun `opening an epub records the open and routes to the epub reader`() = runTest {
        val recorded = mutableListOf<String>()
        val destination = openItemForReading(
            item = item(),
            applicationScope = DefaultApplicationScope(backgroundScope),
            recordItemOpened = { recorded += it },
        )
        runCurrent()

        assertTrue(destination is LibraryNav.Reader)
        assertEquals(listOf("item-1"), recorded)
    }

    @Test
    fun `every openable format records the open`() = runTest {
        val recorded = mutableListOf<String>()
        val scope = DefaultApplicationScope(backgroundScope)

        val pdf = openItemForReading(item(id = "pdf", ebookFormat = EbookFormat.Pdf), scope) { recorded += it }
        val cbz = openItemForReading(item(id = "cbz", ebookFormat = EbookFormat.Cbz), scope) { recorded += it }
        val audio = openItemForReading(
            item = item(id = "audio", ebookFormat = EbookFormat.Unsupported, hasAudio = true),
            applicationScope = scope,
        ) { recorded += it }
        runCurrent()

        assertTrue(pdf is LibraryNav.PdfReader)
        assertTrue(cbz is LibraryNav.CbzReader)
        assertTrue(audio is LibraryNav.AudiobookPlayer)
        assertEquals(listOf("pdf", "cbz", "audio"), recorded)
    }

    @Test
    fun `an item no reader can open records nothing`() = runTest {
        val recorded = mutableListOf<String>()
        val destination = openItemForReading(
            item = item(ebookFormat = EbookFormat.Unsupported),
            applicationScope = DefaultApplicationScope(backgroundScope),
            recordItemOpened = { recorded += it },
        )
        runCurrent()

        assertNull(destination)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a failing record never blocks navigation`() = runTest {
        val destination = openItemForReading(
            item = item(),
            applicationScope = DefaultApplicationScope(backgroundScope),
            recordItemOpened = { error("offline") },
        )
        runCurrent()

        assertTrue(destination is LibraryNav.Reader)
    }
}

package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchAudiobookChaptersUseCaseTest {
    private val chapterRepo = mockk<AudiobookChapterCacheRepository>()
    private val useCase = FetchAudiobookChaptersUseCase(chapterRepo)

    private fun makeItem() = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Book", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported, hasAudio = true, sourceId = "srv1",
    )

    @Test
    fun `returns cached chapters without hitting network`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
        coVerify(exactly = 0) { chapterRepo.fetchAndCacheChapters(any(), any()) }
        coVerify(exactly = 0) { chapterRepo.getStaleCachedChapters(any(), any()) }
    }

    @Test
    fun `fetches from network when no cache`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1") } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
        coVerify(exactly = 0) { chapterRepo.getStaleCachedChapters(any(), any()) }
    }

    /**
     * Regression: the TTL introduced with MIGRATION_54_55 turns getCachedChapters into a
     * stale-nulling read. Without the stale fallback below, an offline user with an 8-day-old
     * cache would lose every chapter (fresh returns null → fetch throws → empty). We must
     * surface the stale row rather than an empty list.
     */
    @Test
    fun `falls back to stale cache when live fetch throws`() = runTest {
        val stale = listOf(AudiobookChapter(0, 0.0, 300.0, "Old Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1") } throws RuntimeException("offline")
        coEvery { chapterRepo.getStaleCachedChapters("srv1", "item1") } returns stale

        val result = useCase(makeItem())

        assertEquals(stale, result)
    }

    @Test
    fun `falls back to stale cache when live fetch returns empty`() = runTest {
        val stale = listOf(AudiobookChapter(0, 0.0, 300.0, "Old Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1") } returns emptyList()
        coEvery { chapterRepo.getStaleCachedChapters("srv1", "item1") } returns stale

        val result = useCase(makeItem())

        assertEquals(stale, result)
    }

    @Test
    fun `returns empty list when live fetch fails and no stale cache exists`() = runTest {
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1") } throws RuntimeException("boom")
        coEvery { chapterRepo.getStaleCachedChapters("srv1", "item1") } returns null

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }
}

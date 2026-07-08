package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
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
    }

    @Test
    fun `fetches from network when no cache`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1") } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
    }

    @Test
    fun `returns empty list on repository error`() = runTest {
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1") } throws RuntimeException("boom")

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }
}

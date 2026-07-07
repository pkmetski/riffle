package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchAudiobookChaptersUseCaseTest {
    private val chapterRepo = mockk<AudiobookChapterCacheRepository>()
    private val serverRepo = mockk<SourceRepository>()
    private val tokenStorage = mockk<TokenStorage>()
    private val useCase = FetchAudiobookChaptersUseCase(chapterRepo, serverRepo, tokenStorage)

    private fun makeItem() = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Book", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported, hasAudio = true, sourceId = "srv1",
    )

    private fun makeServer(baseUrl: String = "http://base") = Source(
        id = "srv1",
        url = SourceUrl.parse(baseUrl)!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "user",
    )

    @Test
    fun `returns cached chapters without hitting network`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
        coVerify(exactly = 0) { chapterRepo.fetchAndCacheChapters(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fetches from network when no cache`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { serverRepo.getById("srv1") } returns makeServer("http://base")
        coEvery { tokenStorage.getToken("srv1") } returns "tok"
        coEvery {
            chapterRepo.fetchAndCacheChapters("srv1", "item1", "http://base", "tok", false)
        } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
    }

    @Test
    fun `returns empty list when server not found`() = runTest {
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { serverRepo.getById("srv1") } returns null

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }
}

package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

class EpubBundleFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun fetch_writesResponseBodyToTempFile() = runTest {
        val epubBytes = "EPUB PAYLOAD".toByteArray()
        val api = StorytellerBundleApi { _, bookId, token, _ ->
            assertEquals("42", bookId)
            assertEquals("tkn", token)
            NetworkStorytellerBundleResult.Success(
                epubBytes.toResponseBody("application/epub+zip".toMediaType()),
            )
        }
        val fetcher = EpubBundleFetcher(api, workingDirProvider = { tmp.root })

        val result = fetcher.fetch(
            baseUrl = "http://stub",
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is EpubBundleFetcher.Result.Success)
        val file = (result as EpubBundleFetcher.Result.Success).epubFile
        assertEquals(epubBytes.toList(), file.readBytes().toList())
    }

    @Test
    fun fetch_whenTempFileCreationFails_closesBodyAndReturnsError() = runTest {
        val closed = AtomicBoolean(false)
        val api = StorytellerBundleApi { _, _, _, _ ->
            val tracked = object : ForwardingSource(Buffer().write("EPUB".toByteArray())) {
                override fun close() { closed.set(true); super.close() }
            }.buffer()
            val body: ResponseBody = tracked.asResponseBody("application/epub+zip".toMediaType(), 4L)
            NetworkStorytellerBundleResult.Success(body)
        }
        // A regular file (not a directory) as the working dir makes createTempFile() throw — the
        // body must still be closed rather than leaked.
        val notADir = tmp.newFile("not-a-dir")
        val fetcher = EpubBundleFetcher(api, workingDirProvider = { notADir })

        val result = fetcher.fetch("http://stub", "42", "tkn", false)

        assertTrue("Body must be closed even when temp-file creation fails", closed.get())
        assertTrue(result is EpubBundleFetcher.Result.NetworkError)
    }

    @Test
    fun fetch_propagatesNetworkError() = runTest {
        val api = StorytellerBundleApi { _, _, _, _ ->
            NetworkStorytellerBundleResult.NetworkError(RuntimeException("boom"))
        }
        val fetcher = EpubBundleFetcher(api, workingDirProvider = { tmp.root })

        val result = fetcher.fetch("http://stub", "42", "tkn", false)

        assertTrue(result is EpubBundleFetcher.Result.NetworkError)
        assertEquals("boom", (result as EpubBundleFetcher.Result.NetworkError).cause.message)
    }
}

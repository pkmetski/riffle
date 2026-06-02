package com.riffle.core.data

import com.riffle.core.network.AudiobookBundleApi
import com.riffle.core.network.NetworkAudiobookBundleResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AudiobookBundleDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val full = ByteArray(200) { it.toByte() }

    /** Fake that records the requested resume offset and serves bytes from [full]. */
    private inner class FakeApi(
        private val honorRange: Boolean = true,
        private val failWith: Throwable? = null,
    ) : AudiobookBundleApi {
        var requestedFromByte: Long = -1
        override suspend fun openBundleStream(
            baseUrl: String,
            bookId: String,
            token: String,
            insecureAllowed: Boolean,
            fromByte: Long,
        ): NetworkAudiobookBundleResult {
            requestedFromByte = fromByte
            failWith?.let { return NetworkAudiobookBundleResult.NetworkError(it) }
            return if (fromByte > 0 && honorRange) {
                val tail = full.copyOfRange(fromByte.toInt(), full.size)
                NetworkAudiobookBundleResult.Success(
                    body = tail.toResponseBody("application/epub+zip".toMediaType()),
                    totalBytes = full.size.toLong(),
                    isPartial = true,
                )
            } else {
                NetworkAudiobookBundleResult.Success(
                    body = full.toResponseBody("application/epub+zip".toMediaType()),
                    totalBytes = full.size.toLong(),
                    isPartial = false,
                )
            }
        }
    }

    private fun downloader(api: AudiobookBundleApi, dir: File) =
        AudiobookBundleDownloader(api = api, targetFileProvider = { File(dir, "$it.epub") })

    @Test fun freshDownload_writesFullBundle_andReportsProgressToCompletion() = runTest {
        val dir = tmp.newFolder()
        var lastDownloaded = 0L
        var lastTotal = 0L

        val result = downloader(FakeApi(), dir).download("u", "42", "t", false) { d, total ->
            lastDownloaded = d; lastTotal = total
        }

        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        val file = (result as AudiobookBundleDownloader.Result.Success).file
        assertArrayEquals(full, file.readBytes())
        assertEquals(200L, lastDownloaded)
        assertEquals(200L, lastTotal)
        assertFalse("partial file cleaned up", File(dir, file.name + ".part").exists())
    }

    @Test fun resume_picksUpFromExistingPartial_andStitchesFullBundle() = runTest {
        val dir = tmp.newFolder()
        val api = FakeApi(honorRange = true)
        // simulate an interrupted download: first 80 bytes already on disk
        val part = File(dir, "42.epub.part")
        part.writeBytes(full.copyOfRange(0, 80))

        val result = downloader(api, dir).download("u", "42", "t", false) { _, _ -> }

        assertEquals(80L, api.requestedFromByte)
        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        assertArrayEquals(full, (result as AudiobookBundleDownloader.Result.Success).file.readBytes())
    }

    @Test fun serverIgnoresRange_restartsFromScratch() = runTest {
        val dir = tmp.newFolder()
        val part = File(dir, "42.epub.part")
        part.writeBytes(full.copyOfRange(0, 80))

        val result = downloader(FakeApi(honorRange = false), dir).download("u", "42", "t", false) { _, _ -> }

        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        assertArrayEquals(full, (result as AudiobookBundleDownloader.Result.Success).file.readBytes())
    }

    @Test fun alreadyDownloaded_returnsExistingFile_withoutHittingNetwork() = runTest {
        val dir = tmp.newFolder()
        val existing = File(dir, "42.epub").apply { writeBytes(full) }
        val api = FakeApi()

        val result = downloader(api, dir).download("u", "42", "t", false) { _, _ -> }

        assertEquals(-1L, api.requestedFromByte) // never called
        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        assertEquals(existing, (result as AudiobookBundleDownloader.Result.Success).file)
    }

    @Test fun networkError_preservesPartialForResume() = runTest {
        val dir = tmp.newFolder()
        val part = File(dir, "42.epub.part").apply { writeBytes(full.copyOfRange(0, 40)) }

        val result = downloader(FakeApi(failWith = IOException("boom")), dir)
            .download("u", "42", "t", false) { _, _ -> }

        assertTrue(result is AudiobookBundleDownloader.Result.NetworkError)
        assertTrue("partial kept for resume", part.exists())
        assertEquals(40, part.length())
    }
}

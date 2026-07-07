package com.riffle.core.data

import com.riffle.core.network.AudiobookBundleApi
import com.riffle.core.network.AudiobookBundleStream
import com.riffle.core.network.NetworkResult
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
        // Serve a body shorter than the advertised totalBytes (a truncating proxy / mid-stream close),
        // without throwing — to exercise the completeness guard.
        private val serveBytes: Int = full.size,
    ) : AudiobookBundleApi {
        var requestedFromByte: Long = -1
        override suspend fun openBundleStream(
            baseUrl: String,
            bookId: String,
            token: String,
            insecureAllowed: Boolean,
            fromByte: Long,
        ): NetworkResult<AudiobookBundleStream> {
            requestedFromByte = fromByte
            failWith?.let { return NetworkResult.Offline(it) }
            return if (fromByte > 0 && honorRange) {
                val tail = full.copyOfRange(fromByte.toInt(), full.size)
                NetworkResult.Success(AudiobookBundleStream(
                    body = tail.toResponseBody("application/epub+zip".toMediaType()),
                    totalBytes = full.size.toLong(),
                    isPartial = true,
                ))
            } else {
                // Advertise the FULL length but serve only [serveBytes] — a silent truncation.
                NetworkResult.Success(AudiobookBundleStream(
                    body = full.copyOfRange(0, serveBytes).toResponseBody("application/epub+zip".toMediaType()),
                    totalBytes = full.size.toLong(),
                    isPartial = false,
                ))
            }
        }
    }

    private fun downloader(api: AudiobookBundleApi, dir: File) =
        AudiobookBundleDownloader(
            api = api,
            targetFileProvider = { _, id -> File(dir, "$id.epub") },
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )

    @Test fun freshDownload_writesFullBundle_andReportsProgressToCompletion() = runTest {
        val dir = tmp.newFolder()
        var lastDownloaded = 0L
        var lastTotal = 0L

        val result = downloader(FakeApi(), dir).download("s1", "u", "42", "t", false) { d, total ->
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

        val result = downloader(api, dir).download("s1", "u", "42", "t", false) { _, _ -> }

        assertEquals(80L, api.requestedFromByte)
        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        assertArrayEquals(full, (result as AudiobookBundleDownloader.Result.Success).file.readBytes())
    }

    @Test fun serverIgnoresRange_restartsFromScratch() = runTest {
        val dir = tmp.newFolder()
        val part = File(dir, "42.epub.part")
        part.writeBytes(full.copyOfRange(0, 80))

        val result = downloader(FakeApi(honorRange = false), dir).download("s1", "u", "42", "t", false) { _, _ -> }

        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        assertArrayEquals(full, (result as AudiobookBundleDownloader.Result.Success).file.readBytes())
    }

    @Test fun alreadyDownloaded_returnsExistingFile_withoutHittingNetwork() = runTest {
        val dir = tmp.newFolder()
        val existing = File(dir, "42.epub").apply { writeBytes(full) }
        val api = FakeApi()

        val result = downloader(api, dir).download("s1", "u", "42", "t", false) { _, _ -> }

        assertEquals(-1L, api.requestedFromByte) // never called
        assertTrue(result is AudiobookBundleDownloader.Result.Success)
        assertEquals(existing, (result as AudiobookBundleDownloader.Result.Success).file)
    }

    @Test fun truncatedStream_isNotFinalised_andPartIsKeptForResume() = runTest {
        val dir = tmp.newFolder()
        // Source advertises 200 bytes but the body ends after 120 — no exception thrown.
        val result = downloader(FakeApi(serveBytes = 120), dir).download("s1", "u", "42", "t", false) { _, _ -> }

        assertTrue("a short body must be treated as a failure, not a complete bundle", result is AudiobookBundleDownloader.Result.NetworkError)
        assertFalse("truncated bundle must not be promoted to the final file", File(dir, "42.epub").exists())
        val part = File(dir, "42.epub.part")
        assertTrue("the received prefix is kept for a resume", part.exists())
        assertEquals(120, part.length())
    }

    @Test fun networkError_preservesPartialForResume() = runTest {
        val dir = tmp.newFolder()
        val part = File(dir, "42.epub.part").apply { writeBytes(full.copyOfRange(0, 40)) }

        val result = downloader(FakeApi(failWith = IOException("boom")), dir)
            .download("s1", "u", "42", "t", false) { _, _ -> }

        assertTrue(result is AudiobookBundleDownloader.Result.NetworkError)
        assertTrue("partial kept for resume", part.exists())
        assertEquals(40, part.length())
    }
}

package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class StorytellerSidecarFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Standard ordering: SMIL before audio. Fast path stops at the audio entry; SMIL already captured.
    private val standardBundle = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to "<smil/>".toByteArray(),
        "text/c1.html" to "<html/>".toByteArray(),
        "Audio/big.mp3" to Random(3).nextBytes(500_000),
    )

    // Non-standard ordering: audio before SMIL. Fast path finds no SMIL; fallback reads it via ZipFile.
    private val nonStandardBundle = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "text/c1.html" to "<html/>".toByteArray(),
        "Audio/track1.mp3" to Random(7).nextBytes(1_024),
        "MediaOverlays/c1.smil" to "<smil/>".toByteArray(),
    )

    // SMIL-less bundle: Storyteller at SPLIT_TRACKS stage — no SMIL anywhere.
    private val smilLessBundle = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "text/c1.html" to "<html>chapter one</html>".toByteArray(),
        "text/c2.html" to "<html>chapter two</html>".toByteArray(),
        "Audio/track1.mp3" to ByteArray(1024),
    )

    private fun fetcher(
        bundleApi: StorytellerBundleApi,
        fullBundleApi: StorytellerBundleApi = bundleApi,
    ) = StorytellerSidecarFetcher(
        bundleApi = bundleApi,
        fullBundleApi = fullBundleApi,
        tempDir = { tmp.root },
    )

    @Test
    fun `standard ordering — keeps non-audio entries and stops before pulling the audio bytes`() = runTest {
        val counting = CountingInputStream(ByteArrayInputStream(standardBundle))
        val result = fetcher(
            bundleApi = StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.Success(counting.source().buffer().asResponseBody(null, -1L))
            },
            // Full download must NOT be called — standard ordering should succeed on the fast path.
            fullBundleApi = StorytellerBundleApi { _, _, _, _ ->
                throw AssertionError("full download should not be triggered for standard-ordering bundle")
            },
        ).fetch("http://st", "42", "tok", false)

        assertTrue(result is StorytellerSidecarFetcher.FetchResult.Success)
        val sidecar = (result as StorytellerSidecarFetcher.FetchResult.Success).bytes
        val names = zipNames(sidecar)
        assertTrue("SMIL is kept", names.contains("MediaOverlays/c1.smil"))
        assertTrue("html is kept", names.contains("text/c1.html"))
        assertFalse("audio is stripped", names.contains("Audio/big.mp3"))
        // Fast path must stop before the 500 KB audio so the prepare stays ~1 MB.
        assertTrue("read ${counting.bytesRead} bytes — must stop before the 500KB audio", counting.bytesRead < 200_000)
    }

    @Test
    fun `non-standard ordering — SMIL after audio is captured via full-download fallback`() = runTest {
        val result = fetcher(
            bundleApi = StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.Success(
                    ByteArrayInputStream(nonStandardBundle).source().buffer().asResponseBody(null, -1L),
                )
            },
            fullBundleApi = StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.Success(
                    ByteArrayInputStream(nonStandardBundle).source().buffer().asResponseBody(null, -1L),
                )
            },
        ).fetch("http://st", "42", "tok", false)

        assertTrue(result is StorytellerSidecarFetcher.FetchResult.Success)
        val sidecar = (result as StorytellerSidecarFetcher.FetchResult.Success).bytes
        val names = zipNames(sidecar)
        assertTrue("SMIL is kept", names.contains("MediaOverlays/c1.smil"))
        assertTrue("html is kept", names.contains("text/c1.html"))
        assertFalse("audio is stripped", names.contains("Audio/track1.mp3"))
    }

    @Test
    fun `returns NotAligned when the bundle has no SMIL anywhere — book not yet aligned`() = runTest {
        // Both the fast streaming attempt and the full-download fallback return the SMIL-less bundle.
        // ZipFile confirms no SMIL exists anywhere → definitively unaligned.
        val result = fetcher(
            bundleApi = StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.Success(
                    ByteArrayInputStream(smilLessBundle).source().buffer().asResponseBody(null, -1L),
                )
            },
            fullBundleApi = StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.Success(
                    ByteArrayInputStream(smilLessBundle).source().buffer().asResponseBody(null, -1L),
                )
            },
        ).fetch("http://st", "42", "tok", false)

        assertTrue(result is StorytellerSidecarFetcher.FetchResult.NotAligned)
    }

    @Test
    fun `returns NetworkError when the bundle transport fails`() = runTest {
        val result = fetcher(
            bundleApi = StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.NetworkError(RuntimeException("offline"))
            },
        ).fetch("http://st", "42", "tok", false)

        assertTrue(result is StorytellerSidecarFetcher.FetchResult.NetworkError)
    }

    private class CountingInputStream(stream: InputStream) : FilterInputStream(stream) {
        var bytesRead = 0L; private set
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead += it }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) { zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun zipNames(bytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names += e.name; e = zis.nextEntry }
        }
        return names
    }
}

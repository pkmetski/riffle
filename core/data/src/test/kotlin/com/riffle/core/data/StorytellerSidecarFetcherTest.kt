package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class StorytellerSidecarFetcherTest {

    // Storyteller's aligned bundle packs text + SMIL FIRST and the (huge) audio LAST — so the sidecar is
    // a small front prefix and the strip can stop at the first audio without reading the audio off the wire.
    private val bundle = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to "<smil/>".toByteArray(),
        "text/c1.html" to "<html/>".toByteArray(),
        "Audio/big.mp3" to Random(3).nextBytes(500_000),
    )

    @Test
    fun `keeps the non-audio prefix, drops audio, and stops before pulling the audio bytes`() = runTest {
        val counting = CountingInputStream(ByteArrayInputStream(bundle))
        val fetcher = StorytellerSidecarFetcher(
            StorytellerBundleApi { _, _, _, _ ->
                NetworkStorytellerBundleResult.Success(counting.source().buffer().asResponseBody(null, -1L))
            },
        )

        val sidecar = fetcher.fetch("http://st", "42", "tok", false)!!
        val names = zipNames(sidecar)
        assertTrue("SMIL is kept", names.contains("MediaOverlays/c1.smil"))
        assertTrue("html is kept", names.contains("text/c1.html"))
        assertFalse("audio is stripped", names.contains("Audio/big.mp3"))
        // Stopping at the first audio entry means the 500 KB audio blob is never read off the wire — this
        // is what keeps the prepare to ~1 MB and far faster than a full download.
        assertTrue("read ${counting.bytesRead} bytes — must stop before the 500KB audio", counting.bytesRead < 200_000)
    }

    @Test
    fun `returns null when the bundle transport fails`() = runTest {
        val fetcher = StorytellerSidecarFetcher(
            StorytellerBundleApi { _, _, _, _ -> NetworkStorytellerBundleResult.NetworkError(RuntimeException("offline")) },
        )
        assertNull(fetcher.fetch("http://st", "42", "tok", false))
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

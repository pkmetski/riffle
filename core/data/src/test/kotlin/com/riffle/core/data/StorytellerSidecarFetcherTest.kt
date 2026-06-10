package com.riffle.core.data

import com.riffle.core.network.NetworkRangeResult
import com.riffle.core.network.NetworkStorytellerBundleSizeResult
import com.riffle.core.network.StorytellerBundleProbeApi
import com.riffle.core.network.StorytellerRangeApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class StorytellerSidecarFetcherTest {

    private val bundle = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to "<smil/>".toByteArray(),
        "text/c1.html" to "<html/>".toByteArray(),
        "Audio/big.mp3" to Random(3).nextBytes(500_000),
    )

    @Test
    fun `fetches the sidecar over byte ranges, dropping the audio`() = runTest {
        var audioBytesServed = 0L
        val fetcher = StorytellerSidecarFetcher(
            probe = StorytellerBundleProbeApi { _, _, _, _ ->
                NetworkStorytellerBundleSizeResult.Success(bundle.size.toLong())
            },
            range = StorytellerRangeApi { _, _, _, _, offset, length ->
                audioBytesServed += length
                NetworkRangeResult.Success(bundle.copyOfRange(offset.toInt(), (offset + length).toInt()))
            },
        )

        val sidecar = fetcher.fetch("http://st", "42", "tok", false)!!
        val names = zipNames(sidecar)
        assertTrue(names.contains("MediaOverlays/c1.smil"))
        assertTrue(names.contains("text/c1.html"))
        assertFalse(names.contains("Audio/big.mp3"))
        // ~70 KB of metadata (EOCD tail scan + central dir + tiny entries) — far below the 500 KB audio,
        // proving the audio span was never requested.
        assertTrue("served $audioBytesServed bytes — must not pull the 500KB audio", audioBytesServed < 200_000)
    }

    @Test
    fun `returns null when the size probe fails`() = runTest {
        val fetcher = StorytellerSidecarFetcher(
            probe = StorytellerBundleProbeApi { _, _, _, _ ->
                NetworkStorytellerBundleSizeResult.NetworkError(RuntimeException("offline"))
            },
            range = StorytellerRangeApi { _, _, _, _, _, _ -> NetworkRangeResult.NetworkError(RuntimeException()) },
        )
        assertNull(fetcher.fetch("http://st", "42", "tok", false))
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

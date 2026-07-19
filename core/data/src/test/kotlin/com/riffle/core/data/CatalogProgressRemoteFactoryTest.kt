package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.common.Clock
import com.riffle.core.domain.EbookCfiTranslator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Catalog-backed [com.riffle.core.domain.ProgressRemote] adapters (ADR 0030 / ADR 0013): translate
 * ABS `epubcfi(...)` ↔ Riffle Locator JSON at the Catalog boundary so the local store is never
 * polluted with a foreign format. A null translator defers (returns null) — row stays dirty for
 * the next sweep once the EPUB is cached. [CfiDialect.PAGE_NUMBER] short-circuits the translator
 * so page-based peers (Komga, #528) don't need CFI plumbing.
 */
class CatalogProgressRemoteFactoryTest {

    private class FakePeer(
        var progress: CatalogProgress? = null,
        var failGet: Boolean = false,
        var failPush: Boolean = false,
        override val cfiDialect: CfiDialect = CfiDialect.EPUB_JS,
    ) : ProgressPeerCapability, AudiobookProgressPeerCapability {
        data class Ebook(val itemId: String, val location: String, val progress: Float, val isFinished: Boolean?, val ts: Long)
        data class Audio(val itemId: String, val currentTimeSec: Double, val durationSec: Double, val isFinished: Boolean?, val ts: Long)
        var lastEbook: Ebook? = null
        var lastAudio: Audio? = null

        override suspend fun pushEbookProgress(
            itemId: String, location: String, progress: Float, isFinished: Boolean?, lastUpdateEpochMs: Long,
        ): Long? {
            if (failPush) throw RuntimeException("down")
            lastEbook = Ebook(itemId, location, progress, isFinished, lastUpdateEpochMs)
            return null
        }

        override suspend fun pushAudiobookProgress(
            itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean?, lastUpdateEpochMs: Long,
        ): Long? {
            if (failPush) throw RuntimeException("down")
            lastAudio = Audio(itemId, currentTimeSec, durationSec, isFinished, lastUpdateEpochMs)
            return null
        }

        override suspend fun pullProgress(itemId: String): CatalogProgress? {
            if (failGet) throw RuntimeException("down")
            return progress
        }

        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()
    }

    private class FakeTranslator(
        private val cfiResult: suspend (String) -> String?,
        private val locatorResult: suspend (String) -> String?,
    ) : EbookCfiTranslator {
        override suspend fun cfiToLocatorJson(epubcfi: String) = cfiResult(epubcfi)
        override suspend fun locatorJsonToCfi(locatorJson: String) = locatorResult(locatorJson)
    }

    private val clock = object : Clock { override fun nowMs() = 1800L; override fun nowNs() = 0L }
    private val locatorJson = """{"href":"OPS/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""

    private fun ebookRemote(peer: ProgressPeerCapability, translator: EbookCfiTranslator?, progress: Float = 0.5f) =
        CatalogEbookProgressRemote(peer, "item-1", translator, { progress }, clock)

    private fun audioRemote(peer: FakePeer, duration: Double = 3600.0) =
        CatalogAudioProgressRemote(peer = peer, itemId = "item-1", duration = { duration }, clock = clock)

    // --- ebook get ---

    @Test
    fun `ebook get - null translator returns null (EPUB not cached, defers row)`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "epubcfi(/6/4!/4)", lastUpdate = 1700L))
        assertNull(ebookRemote(peer, translator = null).get())
    }

    @Test
    fun `ebook get - translates epubcfi to Locator JSON and preserves lastUpdate`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "epubcfi(/6/4!/4)", lastUpdate = 1700L))
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        val read = ebookRemote(peer, translator).get()
        assertEquals(locatorJson, read?.position)
        assertEquals(1700L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - returns null when translation fails (CFI unresolvable)`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "epubcfi(/6/4!/4)", lastUpdate = 1700L))
        val translator = FakeTranslator(cfiResult = { null }, locatorResult = { null })
        assertNull(ebookRemote(peer, translator).get())
    }

    @Test
    fun `ebook get - blank ebookLocation passes through as empty without translation`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "", lastUpdate = 0L))
        val translator = FakeTranslator(cfiResult = { error("should not be called") }, locatorResult = { it })
        val read = ebookRemote(peer, translator).get()
        assertEquals("", read?.position)
        assertEquals(0L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - returns null on network error`() = runTest {
        val peer = FakePeer(failGet = true)
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        assertNull(ebookRemote(peer, translator).get())
    }

    // --- ebook patch ---

    @Test
    fun `ebook patch - null translator returns null without sending PATCH`() = runTest {
        val peer = FakePeer()
        assertNull(ebookRemote(peer, translator = null, progress = 0.73f).patch(locatorJson))
        assertNull(peer.lastEbook)
    }

    @Test
    fun `ebook patch - translates Locator JSON to epubcfi and sends it with progress fraction`() = runTest {
        val peer = FakePeer()
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { "epubcfi(/6/8!/2)" })
        val stamp = ebookRemote(peer, translator, progress = 0.73f).patch(locatorJson)
        assertEquals(1800L, stamp)
        assertEquals("epubcfi(/6/8!/2)", peer.lastEbook?.location)
        assertEquals(0.73f, peer.lastEbook?.progress)
    }

    @Test
    fun `ebook patch - returns null without sending PATCH when translation fails`() = runTest {
        val peer = FakePeer()
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { null })
        assertNull(ebookRemote(peer, translator).patch(locatorJson))
        assertNull(peer.lastEbook)
    }

    @Test
    fun `ebook patch - returns null on network error`() = runTest {
        val peer = FakePeer(failPush = true)
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { it })
        assertNull(ebookRemote(peer, translator).patch("epubcfi(/6/4!/4)"))
    }

    // --- ebook, READIUM_NATIVE dialect: translator MUST be skipped ---

    @Test
    fun `ebook get - READIUM_NATIVE dialect passes ebookLocation through verbatim without translator`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress("item-1", ebookLocation = locatorJson, lastUpdate = 1700L),
            cfiDialect = CfiDialect.READIUM_NATIVE,
        )
        val translator = FakeTranslator(cfiResult = { error("must not translate for READIUM_NATIVE") }, locatorResult = { error("must not translate") })
        val read = ebookRemote(peer, translator).get()
        assertEquals(locatorJson, read?.position)
        assertEquals(1700L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - READIUM_NATIVE dialect succeeds even without a translator`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress("item-1", ebookLocation = locatorJson, lastUpdate = 1700L),
            cfiDialect = CfiDialect.READIUM_NATIVE,
        )
        val read = ebookRemote(peer, translator = null).get()
        assertEquals(locatorJson, read?.position)
    }

    @Test
    fun `ebook patch - READIUM_NATIVE dialect sends Locator JSON verbatim and stamps`() = runTest {
        val peer = FakePeer(cfiDialect = CfiDialect.READIUM_NATIVE)
        val translator = FakeTranslator(cfiResult = { error("must not translate") }, locatorResult = { error("must not translate") })
        val stamp = ebookRemote(peer, translator, progress = 0.42f).patch(locatorJson)
        assertEquals(1800L, stamp)
        assertEquals(locatorJson, peer.lastEbook?.location)
        assertEquals(0.42f, peer.lastEbook?.progress)
    }

    // --- ebook, PAGE_NUMBER dialect: translator MUST be skipped, position passes through (#528) ---

    @Test
    fun `ebook get - PAGE_NUMBER dialect passes page-number location through verbatim without translator`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress("item-1", ebookLocation = "42", lastUpdate = 1700L),
            cfiDialect = CfiDialect.PAGE_NUMBER,
        )
        val read = ebookRemote(peer, translator = null).get()
        assertEquals("42", read?.position)
        assertEquals(1700L, read?.lastUpdate)
    }

    @Test
    fun `ebook patch - PAGE_NUMBER dialect sends the opaque position verbatim`() = runTest {
        val peer = FakePeer(cfiDialect = CfiDialect.PAGE_NUMBER)
        val stamp = ebookRemote(peer, translator = null, progress = 0.5f).patch("42")
        assertEquals(1800L, stamp)
        assertEquals("42", peer.lastEbook?.location)
    }

    // --- audio ---

    @Test
    fun `audio get maps currentTime and lastUpdate`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", audioCurrentTime = 942.0, lastUpdate = 1700L))
        val read = audioRemote(peer).get()
        assertEquals(942.0, read?.position!!, 0.0001)
        assertEquals(1700L, read.lastUpdate)
    }

    @Test
    fun `audio patch sends seconds with the supplied duration and returns the write stamp`() = runTest {
        val peer = FakePeer()
        val stamp = audioRemote(peer).patch(1234.5)
        assertEquals(1800L, stamp)
        assertEquals(1234.5, peer.lastAudio?.currentTimeSec!!, 0.0001)
        assertEquals(3600.0, peer.lastAudio?.durationSec!!, 0.0001)
    }

    @Test
    fun `audio get and patch return null on network error`() = runTest {
        val peer = FakePeer(failGet = true, failPush = true)
        assertNull(audioRemote(peer).get())
        assertNull(audioRemote(peer).patch(10.0))
    }
}

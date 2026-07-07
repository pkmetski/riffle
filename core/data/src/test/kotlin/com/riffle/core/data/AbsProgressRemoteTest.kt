package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkServerProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ABS [com.riffle.core.domain.ProgressRemote] adapters (ADR 0030 / ADR 0013): translate ABS
 * `epubcfi(...)` ↔ Riffle Locator JSON at the ABS boundary so the local store is never polluted
 * with a foreign format. A null translator defers (returns null) — row stays dirty for the next
 * sweep once the EPUB is cached.
 */
class AbsProgressRemoteTest {

    private class FakeSessionApi(
        private val getResult: NetworkResult<NetworkServerProgress>,
        private val syncResult: NetworkResult<Long>,
    ) : AbsSessionApi {
        var ebookPayload: NetworkEbookProgressPayload? = null
        var audioPayload: NetworkAudiobookProgressPayload? = null
        var getItemId: String? = null

        override suspend fun syncEbookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean,
        ): NetworkResult<Long> { ebookPayload = payload; return syncResult }

        override suspend fun syncAudiobookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean,
        ): NetworkResult<Long> { audioPayload = payload; return syncResult }

        override suspend fun getProgress(
            baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
        ): NetworkResult<NetworkServerProgress> { getItemId = libraryItemId; return getResult }
    }

    private class FakeTranslator(
        private val cfiResult: suspend (String) -> String?,
        private val locatorResult: suspend (String) -> String?,
    ) : EbookCfiTranslator {
        override suspend fun cfiToLocatorJson(epubcfi: String) = cfiResult(epubcfi)
        override suspend fun locatorJsonToCfi(locatorJson: String) = locatorResult(locatorJson)
    }

    private fun serverProgress(cfi: String = "", progress: Float = 0f, currentTime: Double = 0.0, lastUpdate: Long = 0L) =
        NetworkServerProgress(ebookLocation = cfi, ebookProgress = progress, currentTime = currentTime, lastUpdate = lastUpdate)

    private val locatorJson = """{"href":"OPS/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""

    // --- ebook get ---

    @Test
    fun `ebook get - null translator returns null (EPUB not cached, defers row)`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress(cfi = "epubcfi(/6/4!/4)", lastUpdate = 1700L)),
            NetworkResult.Success(0L),
        )
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator = null) { 0.5f }
        assertNull(remote.get())
    }

    @Test
    fun `ebook get - translates epubcfi to Locator JSON and preserves lastUpdate`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress(cfi = "epubcfi(/6/4!/4)", lastUpdate = 1700L)),
            NetworkResult.Success(0L),
        )
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        val read = remote.get()
        assertEquals(locatorJson, read?.position)
        assertEquals(1700L, read?.lastUpdate)
        assertEquals("item-1", api.getItemId)
    }

    @Test
    fun `ebook get - returns null when translation fails (CFI unresolvable)`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress(cfi = "epubcfi(/6/4!/4)", lastUpdate = 1700L)),
            NetworkResult.Success(0L),
        )
        val translator = FakeTranslator(cfiResult = { null }, locatorResult = { null })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        assertNull(remote.get())
    }

    @Test
    fun `ebook get - blank ebookLocation (never-opened book) passes through as empty without translation`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress(cfi = "", lastUpdate = 0L)),
            NetworkResult.Success(0L),
        )
        val translator = FakeTranslator(cfiResult = { error("should not be called") }, locatorResult = { it })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        val read = remote.get()
        assertEquals("", read?.position)
        assertEquals(0L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - returns null on network error`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Offline(RuntimeException("down")),
            NetworkResult.Success(0L),
        )
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        assertNull(remote.get())
    }

    // --- ebook patch ---

    @Test
    fun `ebook patch - null translator returns null without sending PATCH`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress()),
            NetworkResult.Success(1800L),
        )
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator = null) { 0.73f }
        assertNull(remote.patch(locatorJson))
        assertNull(api.ebookPayload)
    }

    @Test
    fun `ebook patch - translates Locator JSON to epubcfi and sends it with progress fraction`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress()),
            NetworkResult.Success(1800L),
        )
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { "epubcfi(/6/8!/2)" })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.73f }
        val stamp = remote.patch(locatorJson)
        assertEquals(1800L, stamp)
        assertEquals("epubcfi(/6/8!/2)", api.ebookPayload?.ebookLocation)
        assertEquals(0.73f, api.ebookPayload?.ebookProgress)
    }

    @Test
    fun `ebook patch - returns null without sending PATCH when translation fails`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress()),
            NetworkResult.Success(1800L),
        )
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { null })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        assertNull(remote.patch(locatorJson))
        assertNull(api.ebookPayload)
    }

    @Test
    fun `ebook patch - returns null on network error`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress()),
            NetworkResult.Offline(RuntimeException("down")),
        )
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { it })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        assertNull(remote.patch("epubcfi(/6/4!/4)"))
    }

    @Test
    fun `ebook patch - passes through a legacy raw epubcfi unchanged`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress()),
            NetworkResult.Success(1800L),
        )
        // Simulates EbookCfiTranslatorImpl.locatorJsonToCfi: raw epubcfi passes through
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { input ->
            if (input.startsWith("epubcfi(")) input else null
        })
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1", translator) { 0.5f }
        val stamp = remote.patch("epubcfi(/6/8!/2)")
        assertEquals(1800L, stamp)
        assertEquals("epubcfi(/6/8!/2)", api.ebookPayload?.ebookLocation)
    }

    // --- audio ---

    @Test
    fun `audio get maps currentTime and lastUpdate`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress(currentTime = 942.0, lastUpdate = 1700L)),
            NetworkResult.Success(0L),
        )
        val remote = AbsAudioProgressRemote(api, "http://abs", "tok", false, "item-1") { 3600.0 }
        val read = remote.get()
        assertEquals(942.0, read?.position!!, 0.0001)
        assertEquals(1700L, read.lastUpdate)
    }

    @Test
    fun `audio patch sends seconds with the supplied duration and returns the source stamp`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Success(serverProgress()),
            NetworkResult.Success(1900L),
        )
        val remote = AbsAudioProgressRemote(api, "http://abs", "tok", false, "item-1") { 3600.0 }
        val stamp = remote.patch(1234.5)
        assertEquals(1900L, stamp)
        assertEquals(1234.5, api.audioPayload?.currentTime!!, 0.0001)
        assertEquals(3600.0, api.audioPayload?.duration!!, 0.0001)
    }

    @Test
    fun `audio get and patch return null on network error`() = runTest {
        val api = FakeSessionApi(
            NetworkResult.Offline(RuntimeException("down")),
            NetworkResult.Offline(RuntimeException("down")),
        )
        val remote = AbsAudioProgressRemote(api, "http://abs", "tok", false, "item-1") { 3600.0 }
        assertNull(remote.get())
        assertNull(remote.patch(10.0))
    }
}

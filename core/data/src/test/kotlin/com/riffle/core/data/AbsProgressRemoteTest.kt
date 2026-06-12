package com.riffle.core.data

import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkSyncSessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ABS [com.riffle.core.domain.ProgressRemote] adapters (ADR 0030 slice 4): map the ABS
 * media-progress record to/from one target's canonical coordinate, surface a network failure as
 * `null` (per-target isolation), and carry the auxiliary item metadata each PATCH needs
 * (ebookProgress fraction / audio duration). Driven over a recording fake [AbsSessionApi].
 */
class AbsProgressRemoteTest {

    private class FakeSessionApi(
        private val getResult: NetworkGetProgressResult,
        private val syncResult: NetworkSyncSessionResult,
    ) : AbsSessionApi {
        var ebookPayload: NetworkEbookProgressPayload? = null
        var audioPayload: NetworkAudiobookProgressPayload? = null
        var getItemId: String? = null

        override suspend fun syncEbookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean,
        ): NetworkSyncSessionResult { ebookPayload = payload; return syncResult }

        override suspend fun syncAudiobookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean,
        ): NetworkSyncSessionResult { audioPayload = payload; return syncResult }

        override suspend fun getProgress(
            baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
        ): NetworkGetProgressResult { getItemId = libraryItemId; return getResult }
    }

    private fun serverProgress(cfi: String = "", progress: Float = 0f, currentTime: Double = 0.0, lastUpdate: Long = 0L) =
        NetworkServerProgress(ebookLocation = cfi, ebookProgress = progress, currentTime = currentTime, lastUpdate = lastUpdate)

    // --- ebook ---

    @Test
    fun `ebook get maps ebookLocation and lastUpdate`() = runTest {
        val api = FakeSessionApi(
            NetworkGetProgressResult.Success(serverProgress(cfi = "epubcfi(/6/4!/4)", lastUpdate = 1700L)),
            NetworkSyncSessionResult.Success(0L),
        )
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1") { 0.5f }
        val read = remote.get()
        assertEquals("epubcfi(/6/4!/4)", read?.position)
        assertEquals(1700L, read?.lastUpdate)
        assertEquals("item-1", api.getItemId)
    }

    @Test
    fun `ebook get returns null on network error`() = runTest {
        val api = FakeSessionApi(
            NetworkGetProgressResult.NetworkError(RuntimeException("down")),
            NetworkSyncSessionResult.Success(0L),
        )
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1") { 0.5f }
        assertNull(remote.get())
    }

    @Test
    fun `ebook patch sends the cfi with the supplied progress fraction and returns the server stamp`() = runTest {
        val api = FakeSessionApi(
            NetworkGetProgressResult.Success(serverProgress()),
            NetworkSyncSessionResult.Success(1800L),
        )
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1") { 0.73f }
        val stamp = remote.patch("epubcfi(/6/8!/2)")
        assertEquals(1800L, stamp)
        assertEquals("epubcfi(/6/8!/2)", api.ebookPayload?.ebookLocation)
        assertEquals(0.73f, api.ebookPayload?.ebookProgress)
    }

    @Test
    fun `ebook patch returns null on network error`() = runTest {
        val api = FakeSessionApi(
            NetworkGetProgressResult.Success(serverProgress()),
            NetworkSyncSessionResult.NetworkError(RuntimeException("down")),
        )
        val remote = AbsEbookProgressRemote(api, "http://abs", "tok", false, "item-1") { 0.5f }
        assertNull(remote.patch("cfi"))
    }

    // --- audio ---

    @Test
    fun `audio get maps currentTime and lastUpdate`() = runTest {
        val api = FakeSessionApi(
            NetworkGetProgressResult.Success(serverProgress(currentTime = 942.0, lastUpdate = 1700L)),
            NetworkSyncSessionResult.Success(0L),
        )
        val remote = AbsAudioProgressRemote(api, "http://abs", "tok", false, "item-1") { 3600.0 }
        val read = remote.get()
        assertEquals(942.0, read?.position!!, 0.0001)
        assertEquals(1700L, read.lastUpdate)
    }

    @Test
    fun `audio patch sends seconds with the supplied duration and returns the server stamp`() = runTest {
        val api = FakeSessionApi(
            NetworkGetProgressResult.Success(serverProgress()),
            NetworkSyncSessionResult.Success(1900L),
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
            NetworkGetProgressResult.NetworkError(RuntimeException("down")),
            NetworkSyncSessionResult.NetworkError(RuntimeException("down")),
        )
        val remote = AbsAudioProgressRemote(api, "http://abs", "tok", false, "item-1") { 3600.0 }
        assertNull(remote.get())
        assertNull(remote.patch(10.0))
    }
}

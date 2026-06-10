package com.riffle.app.feature.reader.readaloud

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.data.AudioIdentityResolverImpl
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerSidecarFetcher
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.StorytellerApiClient
import com.riffle.core.network.StorytellerBundleApiImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device integration of the whole streaming assembly (ADR 0028), exercised against a MockWebServer
 * and a real Room DB — no audio output required, so it's deterministic on the headless harness AVD.
 * Covers the three user-facing source decisions: stream when verified, bundle on identity mismatch,
 * bundle when no audiobook is linked.
 */
@RunWith(AndroidJUnit4::class)
class ReadaloudStreamingSessionFactoryAndroidTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private lateinit var db: RiffleDatabase
    private lateinit var baseUrl: String

    private val ST_SERVER = "st"
    private val ABS_SERVER = "abs"
    private val ST_BOOK = "42"
    private val AUDIOBOOK_ITEM = "ab-item"

    // A /synced bundle: one SMIL segment (5s) over an audio entry that the sidecar strips.
    private val bundleZip: ByteArray = run {
        val smil = """
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body>
              <par><text src="../text/c1.xhtml#s1"/><audio src="../audio/c1.mp3" clipBegin="0s" clipEnd="5s"/></par>
            </body></smil>
        """.trimIndent().toByteArray()
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("OEBPS/smil/c1.smil")); z.write(smil); z.closeEntry()
            z.putNextEntry(ZipEntry("OEBPS/text/c1.xhtml")); z.write("<html/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("OEBPS/audio/c1.mp3")); z.write(ByteArray(2048)); z.closeEntry()
        }
        bos.toByteArray()
    }

    private fun storytellerV2(fileSize: Long) =
        """{"audiobook":{"fileSize":$fileSize,"duration":5.0,"manifest":{"readingOrder":[{"duration":5.0}]}}}"""

    private fun absItem(fileSize: Long) =
        """{"id":"$AUDIOBOOK_ITEM","media":{"duration":5.0,"audioFiles":[
           {"ino":"ino-a","index":1,"duration":5.0,"metadata":{"size":$fileSize}}]}}"""

    /** [absFileSize] differing from Storyteller's 1000 makes the identity check fail. */
    private fun startServer(absFileSize: Long = 1000) {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/books/$ST_BOOK/synced") -> {
                        if (request.method == "HEAD") {
                            MockResponse().setHeader("Content-Length", bundleZip.size.toString())
                        } else {
                            val (start, end) = parseRange(request.getHeader("Range"))
                            MockResponse().setResponseCode(206)
                                .setBody(Buffer().write(bundleZip.copyOfRange(start, end + 1)))
                        }
                    }
                    path.startsWith("/api/v2/books/$ST_BOOK") -> MockResponse().setBody(storytellerV2(1000))
                    path.startsWith("/api/items/$AUDIOBOOK_ITEM") -> MockResponse().setBody(absItem(absFileSize))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    private fun parseRange(header: String?): Pair<Int, Int> {
        val spec = header!!.substringAfter("bytes=").split("-")
        return spec[0].toInt() to spec[1].toInt()
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, RiffleDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        db.close()
    }

    private fun seed(withAudiobook: Boolean) = runBlocking {
        db.serverDao().upsert(ServerEntity(ABS_SERVER, baseUrl, true, false, "u", ServerType.AUDIOBOOKSHELF.name))
        db.serverDao().upsert(ServerEntity(ST_SERVER, baseUrl, false, false, "u", ServerType.STORYTELLER.name))
        if (withAudiobook) {
            db.libraryItemDao().upsertAll(
                listOf(
                    LibraryItemEntity(
                        serverId = ABS_SERVER, id = AUDIOBOOK_ITEM, libraryId = "lib",
                        title = "Book", author = "A", coverUrl = null, readingProgress = 0f, hasAudio = true,
                    ),
                ),
            )
            db.readaloudLinkDao().upsert(
                ReadaloudLinkEntity(ABS_SERVER, AUDIOBOOK_ITEM, ST_SERVER, ST_BOOK, userConfirmed = true, createdAt = 1, updatedAt = 1),
            )
        }
    }

    private fun factory(): ReadaloudStreamingSessionFactory {
        val repo = StubServerRepository(mapOf(ABS_SERVER to baseUrl, ST_SERVER to baseUrl))
        val fetcher = StorytellerBundleApiImpl(OkHttpClient()).let { StorytellerSidecarFetcher(it, it) }
        return ReadaloudStreamingSessionFactory(
            context = ctx,
            audioIdentityResolver = AudioIdentityResolverImpl(db.readaloudLinkDao(), db.libraryItemDao()),
            absApi = AbsApiClient(OkHttpClient()),
            storytellerApi = StorytellerApiClient(OkHttpClient()),
            sidecarStore = ReadaloudSidecarStore(ctx, fetcher, repo, StubTokenStorage),
            serverRepository = repo,
            tokenStorage = StubTokenStorage,
        )
    }

    @Test
    fun verified_match_builds_a_streaming_session() = runBlocking {
        startServer(absFileSize = 1000) // matches Storyteller
        seed(withAudiobook = true)

        val session = factory().tryBuild(ST_SERVER, ST_BOOK)

        assertNotNull("expected a streaming session for a verified match", session)
        assertTrue(session!!.track.clips.isNotEmpty())
        val item = session.streaming.itemsByMediaId["OEBPS/audio/c1.mp3"]
        assertNotNull("segment should be keyed by its audioSrc", item)
        assertEquals("$baseUrl/api/items/$AUDIOBOOK_ITEM/file/ino-a", item!!.url)
        assertEquals(0L, item.clipStartMs)
        assertEquals(5000L, item.clipEndMs)
    }

    @Test
    fun identity_mismatch_falls_back_to_bundle() = runBlocking {
        startServer(absFileSize = 9_999) // differs from Storyteller's 1000 → MISMATCH
        seed(withAudiobook = true)

        assertNull("a recording mismatch must not stream", factory().tryBuild(ST_SERVER, ST_BOOK))
    }

    @Test
    fun no_linked_audiobook_falls_back_to_bundle() = runBlocking {
        startServer()
        seed(withAudiobook = false)

        assertNull("no audiobook link → not streamable", factory().tryBuild(ST_SERVER, ST_BOOK))
    }

    private class StubServerRepository(private val urls: Map<String, String>) : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = emptyFlow()
        override suspend fun getActive(): Server? = null
        override suspend fun getById(serverId: String): Server? =
            urls[serverId]?.let { Server(serverId, ServerUrl.parse(it)!!, false, false, "u", ServerType.AUDIOBOOKSHELF) }
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: ServerType): AuthenticateResult =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private object StubTokenStorage : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) = Unit
        override suspend fun getToken(serverId: String): String = "tok"
        override suspend fun deleteToken(serverId: String) = Unit
    }
}

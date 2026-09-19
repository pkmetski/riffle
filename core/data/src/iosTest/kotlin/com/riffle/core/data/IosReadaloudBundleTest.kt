package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS counterpart to core:data's androidHostTest ReadaloudAudioRepositoryImplTest (issue #1065),
 * driving the real path: a Storyteller bundle zip on disk, read through [IosZipArchive], its
 * `.smil` entries parsed by ksoup, and the resulting timeline mapped to the shared
 * [com.riffle.core.domain.ReadaloudTrack].
 */
@OptIn(ExperimentalForeignApi::class)
class IosReadaloudBundleTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private object NoopSourceRepository : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private object NoopTokenStorage : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "readaloud_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            IosAudiobookFiles.mkdirs(base)
            val full = if (relativePath.isEmpty()) base else "$base/$relativePath"
            full.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { IosAudiobookFiles.mkdirs(it) }
            return full
        }
    }

    private fun repo(fileStore: FileStore) = IosReadaloudAudioRepositoryImpl(
        fileStore = fileStore,
        sourceRepository = NoopSourceRepository,
        tokenStorage = NoopTokenStorage,
        httpClient = HttpClient(MockEngine { respondOk() }),
        dispatchers = IosDispatcherProvider,
    )

    private fun smil(chapter: String, audio: String, vararg sentences: Triple<String, String, String>) = buildString {
        append("""<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq>""")
        sentences.forEach { (id, begin, end) ->
            append("""<par><text src="../text/$chapter#$id"/>""")
            append("""<audio src="../audio/$audio" clipBegin="$begin" clipEnd="$end"/></par>""")
        }
        append("</seq></body></smil>")
    }

    /** Writes a minimal Storyteller-shaped bundle: two chapters, one `.smil` each, plus audio. */
    private fun writeBundle(path: String) {
        val entries = listOf(
            "OEBPS/smil/part0001.smil" to smil(
                "c1.xhtml",
                "c1.mp3",
                Triple("s1", "0s", "2s"),
                Triple("s2", "2s", "5s"),
            ).encodeToByteArray(),
            "OEBPS/smil/part0002.smil" to smil(
                "c2.xhtml",
                "c2.mp3",
                Triple("s1", "0s", "4s"),
            ).encodeToByteArray(),
            "OEBPS/audio/c1.mp3" to ByteArray(32) { 1 },
            "OEBPS/audio/c2.mp3" to ByteArray(16) { 2 },
        )
        IosAudiobookFiles.writeBytes(path, buildStoredZip(entries))
    }

    @Test
    fun `isAudioAvailable is false until a bundle is stored`() {
        val fileStore = TempFileStore()

        assertFalse(repo(fileStore).isAudioAvailable("st-1", "b1"))
    }

    @Test
    fun `isAudioAvailable sees a downloaded bundle`() {
        val fileStore = TempFileStore()
        writeBundle(fileStore.resolve(NS_EPUB_DOWNLOADS, "st-1/b1.epub"))

        assertTrue(repo(fileStore).isAudioAvailable("st-1", "b1"))
    }

    @Test
    fun `a downloaded bundle wins over the cached copy`() {
        val fileStore = TempFileStore()
        val download = fileStore.resolve(NS_EPUB_DOWNLOADS, "st-1/b1.epub")
        writeBundle(download)
        writeBundle(fileStore.resolve(NS_EPUB_CACHE, "st-1/b1.epub"))

        assertEquals(download, repo(fileStore).bundlePath("st-1", "b1"))
    }

    @Test
    fun `readTrack parses every smil entry in name order with resolved hrefs`() = runTest {
        val fileStore = TempFileStore()
        writeBundle(fileStore.resolve(NS_EPUB_DOWNLOADS, "st-1/b1.epub"))

        val track = repo(fileStore).readTrack("st-1", "b1")

        assertTrue(track != null)
        assertEquals(3, track.clips.size, "two chapters: 2 + 1 clips")
        // "../text/c1.xhtml#s1" relative to "OEBPS/smil" resolves to "OEBPS/text/c1.xhtml#s1".
        assertEquals(
            listOf("OEBPS/text/c1.xhtml#s1", "OEBPS/text/c1.xhtml#s2", "OEBPS/text/c2.xhtml#s1"),
            track.clips.map { it.textFragmentRef },
        )
        assertEquals(listOf("OEBPS/audio/c1.mp3", "OEBPS/audio/c1.mp3", "OEBPS/audio/c2.mp3"), track.clips.map { it.audioSrc })
        assertEquals(2, track.chapterCount)
    }

    @Test
    fun `readTrack returns null when no bundle is stored`() = runTest {
        assertNull(repo(TempFileStore()).readTrack("st-1", "b1"))
    }

    @Test
    fun `readAudio returns the clip's audio bytes from inside the bundle`() = runTest {
        val fileStore = TempFileStore()
        writeBundle(fileStore.resolve(NS_EPUB_DOWNLOADS, "st-1/b1.epub"))

        val bytes = repo(fileStore).readAudio("st-1", "b1", "OEBPS/audio/c2.mp3")

        assertTrue(bytes != null)
        assertEquals(16, bytes.size)
    }

    @Test
    fun `removeAudio deletes both copies and reports freed bytes`() = runTest {
        val fileStore = TempFileStore()
        writeBundle(fileStore.resolve(NS_EPUB_DOWNLOADS, "st-1/b1.epub"))
        writeBundle(fileStore.resolve(NS_EPUB_CACHE, "st-1/b1.epub"))
        val repo = repo(fileStore)

        val freed = repo.removeAudio("st-1", "b1")

        assertTrue(freed > 0L, "expected the two bundle copies to report freed bytes")
        assertFalse(repo.isAudioAvailable("st-1", "b1"))
    }
}

// Minimal STORED-only ZIP writer — IosZipArchive reads COMPRESSION_STORED entries verbatim and
// does not validate CRC-32, so tests can build a bundle without a DEFLATE compressor.
private fun buildStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val locals = mutableListOf<ByteArray>()
    val centrals = mutableListOf<ByteArray>()
    var offset = 0

    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val local = (
            le32(0x04034B50) + le16(20) + le16(0) + le16(0) + le16(0) + le16(0) +
                le32(0) + le32(data.size) + le32(data.size) + le16(nameBytes.size) + le16(0)
            ) + nameBytes + data
        locals += local

        centrals += (
            le32(0x02014B50) + le16(20) + le16(20) + le16(0) + le16(0) + le16(0) + le16(0) +
                le32(0) + le32(data.size) + le32(data.size) + le16(nameBytes.size) + le16(0) +
                le16(0) + le16(0) + le16(0) + le32(0) + le32(offset)
            ) + nameBytes
        offset += local.size
    }

    val centralDirectory = centrals.reduce { acc, b -> acc + b }
    val eocd = le32(0x06054B50) + le16(0) + le16(0) + le16(entries.size) + le16(entries.size) +
        le32(centralDirectory.size) + le32(offset) + le16(0)
    return locals.reduce { acc, b -> acc + b } + centralDirectory + eocd
}

private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

private fun le32(v: Int) = byteArrayOf(
    (v and 0xFF).toByte(),
    ((v shr 8) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(),
    ((v shr 24) and 0xFF).toByte(),
)

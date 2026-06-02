package com.riffle.app.feature.library

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryItemDetailReadaloudDownloadTest {

    // Documents the repository contract the VM uses (both downloadAudio overloads, keyed by id).
    private class FakeAudioRepo(private var present: Boolean) : ReadaloudAudioRepository {
        var lastDownloadBookId: String? = null
        var lastDownloadServerId: String? = null
        var lastRemoveBookId: String? = null
        override fun isAudioAvailable(itemId: String) = present
        override fun bundleFile(itemId: String): File? = if (present) File("x") else null
        override suspend fun readTrack(itemId: String): ReadaloudTrack? = null
        override suspend fun probeSizeBytes(itemId: String): Long? = null
        override suspend fun downloadAudio(itemId: String, onProgress: (Long, Long) -> Unit) =
            downloadAudio(itemId, "active", onProgress)
        override suspend fun downloadAudio(bookId: String, serverId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult {
            lastDownloadBookId = bookId; lastDownloadServerId = serverId; present = true
            return AudioDownloadResult.Success
        }
        override suspend fun removeAudio(itemId: String): Long { lastRemoveBookId = itemId; present = false; return 0L }
    }

    @Test fun download_state_maps_from_bundle_presence() {
        assertEquals(DownloadState.Downloaded, readaloudDownloadStateFor(bundlePresent = true))
        assertEquals(DownloadState.NotDownloaded, readaloudDownloadStateFor(bundlePresent = false))
    }
}

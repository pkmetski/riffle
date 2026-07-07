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
        override fun isAudioAvailable(sourceId: String, itemId: String) = present
        override fun bundleFile(sourceId: String, itemId: String): File? = if (present) File("x") else null
        override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = null
        override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(sourceId: String, bookId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult {
            lastDownloadBookId = bookId; lastDownloadServerId = sourceId; present = true
            return AudioDownloadResult.Success
        }
        override suspend fun removeAudio(sourceId: String, itemId: String): Long { lastRemoveBookId = itemId; present = false; return 0L }
    }

    @Test fun download_state_maps_from_bundle_presence() {
        assertEquals(DownloadState.Downloaded, readaloudDownloadStateFor(bundlePresent = true))
        assertEquals(DownloadState.NotDownloaded, readaloudDownloadStateFor(bundlePresent = false))
    }
}

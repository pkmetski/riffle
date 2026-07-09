package com.riffle.core.data.localfiles

import android.net.Uri
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.SourceType
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "install a LocalFiles Source" side of the Add-Source flow. Materialises the singleton
 * LocalFiles [SourceEntity] on first use (there is at most one LocalFiles source per device — the
 * user's multi-folder model runs *inside* that source), attaches the picked SAF folder to it via
 * [LocalFilesFolderRepository], and kicks the initial [LocalFilesScanner.scan].
 *
 * Not part of [com.riffle.core.domain.SourceRepository.commit] because there is no
 * [com.riffle.core.domain.PendingSource] step (no credentials to authenticate, no library set to
 * pick) — the whole flow collapses to "user picked a folder".
 */
@Singleton
class LocalFilesSourceInstaller @Inject constructor(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val folderRepository: LocalFilesFolderRepository,
    private val scanner: LocalFilesScanner,
    private val logger: Logger,
) {

    data class InstallResult(
        val sourceId: String,
        val scan: LocalFilesScanner.ScanReport,
    )

    /**
     * Adds [treeUri] to the LocalFiles source, creating the source row and its synthetic
     * `local:root` library on first call. Runs a first scan of the folder and returns the
     * outcome so callers can surface add/failure counts.
     */
    suspend fun installFolder(treeUri: Uri): InstallResult {
        logger.d(LogChannel.LocalFiles) { "installFolder start uri=$treeUri" }
        val sourceId = ensureLocalFilesSource()
        logger.d(LogChannel.LocalFiles) { "installFolder sourceId=$sourceId" }
        folderRepository.addFolder(sourceId, treeUri)
        logger.d(LogChannel.LocalFiles) { "installFolder folder attached, starting scan" }
        val report = scanner.scan(sourceId)
        logger.d(LogChannel.LocalFiles) {
            "installFolder scan done added=${report.added} refreshed=${report.refreshed} " +
                "removed=${report.removed} failures=${report.failures.size} " +
                "failureSample=${report.failures.take(3).joinToString { "${it.displayName}:${it.reason}" }}"
        }
        return InstallResult(sourceId = sourceId, scan = report)
    }

    /**
     * The LocalFiles source id. Creates one on first call; subsequent calls return the same id.
     * Idempotent: exposed for the Settings folder-management view so it can query "what folders
     * are configured?" before any folder-picker flow has run.
     */
    suspend fun ensureLocalFilesSource(): String {
        sourceDao.getByType(SourceType.LOCAL_FILES.name)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val entity = SourceEntity(
            id = id,
            // No meaningful URL; placeholder parses through [SourceUrl.parse] cleanly and is
            // never network-called (LocalFiles has no server).
            url = LOCAL_FILES_URL_PLACEHOLDER,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "",
            serverType = "AUDIOBOOKSHELF",
            type = SourceType.LOCAL_FILES.name,
        )
        val inserted = sourceDao.upsertAsFirstIfNoActive(entity)
        libraryDao.upsertAll(
            listOf(
                LibraryEntity(
                    id = LocalFilesCatalog.LOCAL_ROOT_ID,
                    name = "Local Files",
                    mediaType = "book",
                    sourceId = inserted.id,
                ),
            ),
        )
        return inserted.id
    }

    companion object {
        const val LOCAL_FILES_URL_PLACEHOLDER: String = "https://localfiles.invalid"
    }
}

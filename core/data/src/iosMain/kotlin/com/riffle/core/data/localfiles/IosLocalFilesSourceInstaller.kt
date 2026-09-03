package com.riffle.core.data.localfiles

import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import platform.Foundation.NSUUID

class IosLocalFilesSourceInstaller(
    private val sourceDao: SourceDao,
    private val folderRepository: IosLocalFilesFolderRepository,
    private val scanner: IosLocalFilesScanner,
) : LocalFilesInstallerInterface {

    data class InstallResult(
        val sourceId: String,
        val libraryId: String,
        val scan: IosLocalFilesScanner.ScanReport,
    )

    override suspend fun installFolder(folderUri: FolderUri): LocalFilesInstallerInterface.InstallReport {
        val result = installFolderDetailed(folderUri)
        return LocalFilesInstallerInterface.InstallReport(
            added = result.scan.added,
            failures = result.scan.failures.size,
        )
    }

    suspend fun installFolderDetailed(folderUri: FolderUri): InstallResult {
        val sourceId = ensureLocalFilesSource()
        val libraryId = folderRepository.addFolder(sourceId, folderUri)
        val report = scanner.scan(sourceId)
        return InstallResult(sourceId = sourceId, libraryId = libraryId, scan = report)
    }

    suspend fun ensureLocalFilesSource(): String {
        sourceDao.getByType(LOCAL_FILES_TYPE)?.let { return it.id }
        val id = NSUUID().UUIDString()
        val entity = SourceEntity(
            id = id,
            url = LOCAL_FILES_URL_PLACEHOLDER,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "",
            serverType = "AUDIOBOOKSHELF",
            type = LOCAL_FILES_TYPE,
        )
        return sourceDao.upsertAsFirstIfNoActive(entity).id
    }

    companion object {
        const val LOCAL_FILES_URL_PLACEHOLDER = "https://localfiles.invalid"
        const val LOCAL_FILES_TYPE = "LOCAL_FILES"
    }
}

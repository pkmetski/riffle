package com.riffle.core.data.localfiles

import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
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
        sourceDao.getByType(SourceType.LOCAL_FILES.name)?.let { return it.id }
        val id = NSUUID().UUIDString()
        val entity = SourceEntity(
            id = id,
            url = LOCAL_FILES_URL_PLACEHOLDER,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "",
            serverType = ServerType.AUDIOBOOKSHELF.name,
            type = SourceType.LOCAL_FILES.name,
        )
        return sourceDao.upsertAsFirstIfNoActive(entity).id
    }

    companion object {
        const val LOCAL_FILES_URL_PLACEHOLDER = "https://localfiles.invalid"
    }
}

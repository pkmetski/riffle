package com.riffle.core.data.localfiles

interface LocalFilesInstallerInterface {
    suspend fun installFolder(folderUri: FolderUri): InstallReport
    data class InstallReport(val added: Int, val failures: Int)
}

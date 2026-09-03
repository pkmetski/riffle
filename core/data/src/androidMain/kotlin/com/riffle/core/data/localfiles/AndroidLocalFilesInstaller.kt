package com.riffle.core.data.localfiles

import android.net.Uri

/** Adapts the Android-specific [LocalFilesSourceInstaller] to the platform-agnostic
 * [LocalFilesInstallerInterface] used by shared Compose UI. */
class AndroidLocalFilesInstaller(
    private val delegate: LocalFilesSourceInstaller,
) : LocalFilesInstallerInterface {

    override suspend fun installFolder(folderUri: FolderUri): LocalFilesInstallerInterface.InstallReport {
        val uri = Uri.parse(folderUri.value)
        val result = delegate.installFolder(uri)
        return LocalFilesInstallerInterface.InstallReport(
            added = result.scan.added,
            failures = result.scan.failures.size,
        )
    }
}

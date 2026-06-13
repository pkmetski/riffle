package com.riffle.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.riffle.core.domain.ApkInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Launches the system package installer for a downloaded APK via the app's [FileProvider]. The
 * install is signed with the same release key, so Android performs an in-place update; the user
 * still confirms at the system install prompt (and grants "install unknown apps" the first time).
 */
class AndroidApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkInstaller {

    override fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

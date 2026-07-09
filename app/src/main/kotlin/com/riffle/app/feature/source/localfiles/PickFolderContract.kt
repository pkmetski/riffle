package com.riffle.app.feature.source.localfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * SAF folder picker: launches [Intent.ACTION_OPEN_DOCUMENT_TREE] with persistable-URI grant flags
 * and returns the picked tree URI, or `null` if the user cancelled. Callers must then invoke
 * `ContentResolver.takePersistableUriPermission(...)` to keep read access across process death.
 */
class PickFolderContract : ActivityResultContract<Unit, Uri?>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

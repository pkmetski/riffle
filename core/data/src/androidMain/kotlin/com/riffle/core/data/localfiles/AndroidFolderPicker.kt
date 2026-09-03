package com.riffle.core.data.localfiles

import android.net.Uri

class AndroidFolderPicker : FolderPickerInterface {

    private var launcher: (() -> Unit)? = null
    private var pendingResult: ((FolderUri?) -> Unit)? = null

    fun setLauncher(launch: () -> Unit) {
        launcher = launch
    }

    fun deliverResult(uri: Uri?) {
        val callback = pendingResult
        pendingResult = null
        callback?.invoke(uri?.let { FolderUri(it.toString()) })
    }

    override fun pickFolder(onResult: (FolderUri?) -> Unit) {
        pendingResult = onResult
        val launch = launcher
        if (launch != null) {
            launch()
        } else {
            pendingResult = null
            onResult(null)
        }
    }
}

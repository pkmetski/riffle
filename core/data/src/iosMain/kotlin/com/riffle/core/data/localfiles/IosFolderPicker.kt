package com.riffle.core.data.localfiles

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class IosFolderPicker : FolderPickerInterface {

    private var activeDelegate: FolderPickerDelegate? = null

    override fun pickFolder(onResult: (FolderUri?) -> Unit) {
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTType.folder),
        )
        val delegate = FolderPickerDelegate(onResult) { activeDelegate = null }
        activeDelegate = delegate
        picker.delegate = delegate
        picker.allowsMultipleSelection = false

        val rootVc = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootVc?.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FolderPickerDelegate(
    private val onResult: (FolderUri?) -> Unit,
    private val onDone: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onDone()
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        onResult(url?.path?.let { FolderUri(it) })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDone()
        onResult(null)
    }
}

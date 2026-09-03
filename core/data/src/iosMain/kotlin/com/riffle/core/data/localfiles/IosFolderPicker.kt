package com.riffle.core.data.localfiles

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class IosFolderPicker : FolderPickerInterface {

    private var activeDelegate: FolderPickerDelegate? = null

    override fun pickFolder(onResult: (FolderUri?) -> Unit) {
        // Drop duplicate concurrent requests — a picker is already active.
        if (activeDelegate != null) return

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTType.folder),
        )
        val delegate = FolderPickerDelegate(onResult) { activeDelegate = null }
        activeDelegate = delegate
        picker.delegate = delegate
        picker.allowsMultipleSelection = false

        val rootVc = keyWindow()?.rootViewController ?: return
        rootVc.presentViewController(picker, animated = true, completion = null)
    }

    private fun keyWindow(): UIWindow? {
        // keyWindow is deprecated in iOS 13+. Walk connected scenes to find the key window.
        return UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? platform.UIKit.UIWindowScene }
            .flatMap { it.windows as List<UIWindow> }
            .firstOrNull { it.isKeyWindow }
            ?: UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow }
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

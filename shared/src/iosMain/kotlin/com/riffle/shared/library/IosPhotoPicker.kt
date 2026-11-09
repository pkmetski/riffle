package com.riffle.shared.library

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemProvider
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * Shows a system PHPickerViewController to let the user pick one image from their photo library.
 * Returns the picked image as a stable `file://` temp path string via [onResult], or null if
 * cancelled. The temp file is valid for the session; callers (e.g. [IosCoverImageCopier]) are
 * responsible for copying it to permanent storage before the session ends.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPhotoPicker {

    private var activeDelegate: PhotoPickerDelegate? = null

    fun pick(onResult: (String?) -> Unit) {
        if (activeDelegate != null) return

        val config = PHPickerConfiguration()
        config.setFilter(PHPickerFilter.imagesFilter)
        config.setSelectionLimit(1)

        val picker = PHPickerViewController(configuration = config)
        val delegate = PhotoPickerDelegate(onResult) { activeDelegate = null }
        activeDelegate = delegate
        picker.setDelegate(delegate)

        val rootVc = keyWindow()?.rootViewController ?: run {
            onResult(null)
            return
        }
        rootVc.presentViewController(picker, animated = true, completion = null)
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? UIWindowScene }
            .flatMap { it.windows as List<UIWindow> }
            .firstOrNull { it.isKeyWindow() }
            ?: UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }
}

@OptIn(ExperimentalForeignApi::class)
private class PhotoPickerDelegate(
    private val onResult: (String?) -> Unit,
    private val onDone: () -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        onDone()
        picker.dismissViewControllerAnimated(true, completion = null)

        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        val provider: NSItemProvider = result?.itemProvider ?: run {
            onResult(null)
            return
        }

        val imageType = UTTypeImage.identifier
        if (!provider.hasItemConformingToTypeIdentifier(imageType)) {
            onResult(null)
            return
        }

        provider.loadDataRepresentationForTypeIdentifier(imageType) { data, _ ->
            if (data == null || data.length == 0uL) {
                onResult(null)
                return@loadDataRepresentationForTypeIdentifier
            }
            val path = writeTempFile(data)
            onResult(path?.let { "file://$it" })
        }
    }

    private fun writeTempFile(data: NSData): String? {
        val dir = NSTemporaryDirectory()
        val path = "${dir}riffle_cover_pick_${platform.Foundation.NSDate().timeIntervalSince1970.toLong()}.jpg"
        val written = data.writeToFile(path, atomically = true)
        return if (written) path else null
    }
}

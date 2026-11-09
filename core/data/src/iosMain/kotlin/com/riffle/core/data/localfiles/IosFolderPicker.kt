package com.riffle.core.data.localfiles

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationMinimalBookmark
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalForeignApi::class)
class IosFolderPicker : FolderPickerInterface {

    private var activeDelegate: FolderPickerDelegate? = null

    override fun pickFolder(onResult: (FolderUri?) -> Unit) {
        if (activeDelegate != null) return

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeFolder),
        )
        val delegate = FolderPickerDelegate(onResult) { activeDelegate = null }
        activeDelegate = delegate
        picker.delegate = delegate
        picker.allowsMultipleSelection = false

        val rootVc = keyWindow()?.rootViewController ?: return
        rootVc.presentViewController(picker, animated = true, completion = null)
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? platform.UIKit.UIWindowScene }
            .flatMap { it.windows as List<UIWindow> }
            .firstOrNull { it.isKeyWindow() }
            ?: UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }

    companion object {

        /**
         * Resolves a [FolderUri] stored as a bookmark back to a live URL with
         * security-scoped access. For plain path strings (pre-bookmark URIs), falls
         * back to the path as-is so old entries continue to work.
         *
         * Returns null when the bookmark is stale (folder moved or deleted).
         */
        @OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
        fun resolveBookmark(treeUri: String): SecurityScopedFolder? {
            if (!treeUri.startsWith("bookmark:")) {
                return if (NSFileCoordinator().let { true }) {
                    SecurityScopedFolder(path = treeUri, url = null)
                } else {
                    null
                }
            }
            val base64 = treeUri.removePrefix("bookmark:")
            val bytes = runCatching { Base64.decode(base64) }.getOrNull() ?: return null
            val data: NSData = if (bytes.isEmpty()) {
                NSData()
            } else {
                bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
            }
            return memScoped {
                val stale = alloc<BooleanVar>()
                val errBox = alloc<ObjCObjectVar<NSError?>>()
                val url = NSURL.URLByResolvingBookmarkData(
                    bookmarkData = data,
                    options = NSURLBookmarkResolutionWithoutUI,
                    relativeToURL = null,
                    bookmarkDataIsStale = stale.ptr,
                    error = errBox.ptr,
                ) ?: return@memScoped null
                val path = url.path ?: return@memScoped null
                SecurityScopedFolder(path = path, url = url)
            }
        }

        @OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
        fun bookmarkUri(url: NSURL): String? = memScoped {
            val errBox = alloc<ObjCObjectVar<NSError?>>()
            val data = url.bookmarkDataWithOptions(
                options = NSURLBookmarkCreationMinimalBookmark,
                includingResourceValuesForKeys = null,
                relativeToURL = null,
                error = errBox.ptr,
            ) ?: return@memScoped null
            val length = data.length.toInt()
            val bytes = ByteArray(length).also { buf ->
                if (length > 0) {
                    buf.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
                }
            }
            "bookmark:${Base64.encode(bytes)}"
        }
    }
}

/**
 * A resolved security-scoped folder path. Call [close] when done to relinquish access.
 * When [url] is null (legacy plain-path entry), close is a no-op.
 */
class SecurityScopedFolder(val path: String, private val url: NSURL?) {
    fun startAccessing(): Boolean = url?.startAccessingSecurityScopedResource() ?: true
    fun stopAccessing() = url?.stopAccessingSecurityScopedResource()
    fun close() = stopAccessing()
}

@OptIn(ExperimentalForeignApi::class)
private class FolderPickerDelegate(
    private val onResult: (FolderUri?) -> Unit,
    private val onDone: () -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onDone()
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onResult(null)
            return
        }
        url.startAccessingSecurityScopedResource()
        val bookmarkUri = IosFolderPicker.bookmarkUri(url)
        url.stopAccessingSecurityScopedResource()
        onResult(bookmarkUri?.let { FolderUri(it) } ?: url.path?.let { FolderUri(it) })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDone()
        onResult(null)
    }
}

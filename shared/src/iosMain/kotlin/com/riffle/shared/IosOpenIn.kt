package com.riffle.shared

import com.riffle.core.data.localfiles.OpenInImportFeed
import com.riffle.core.data.localfiles.OpenInImportResult
import com.riffle.core.data.localfiles.OpenInImporter
import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.Foundation.NSFileManager

/**
 * The Kotlin end of "Open in Riffle" on iOS.
 *
 * Swift's `onOpenURL` copies the incoming document out of the security-scoped (or Inbox) URL
 * into a temporary file it owns, then calls this with that path. Everything after that —
 * classification, the copy into the managed imports folder, the Local Files scan, and the
 * snackbar the user sees — is the shared [OpenInImporter] both platforms run.
 *
 * Runs on the application scope, not a screen scope: the file usually arrives with the launch
 * that opened the app, before any composition exists.
 *
 * No default arguments: a Kotlin default does not cross the Objective-C boundary, so Swift would
 * see a parameter it has to pass anyway and only `xcodebuild` would catch the mismatch.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@Suppress("unused") // Called from Swift: IosOpenInKt.importIncomingFile(path:displayName:)
fun importIncomingFile(path: String, displayName: String) {
    val koin = KoinPlatform.getKoin()
    val importer: OpenInImporter = koin.get()
    val feed: OpenInImportFeed = koin.get()
    val scope: ApplicationScope = koin.get()
    scope.coroutineScope.launch {
        val result = try {
            importer.importFile(locator = path, displayName = displayName)
        } catch (e: Exception) {
            OpenInImportResult.Failed(displayName, e.message ?: "import failed")
        }
        // The Swift side staged the bytes into NSTemporaryDirectory to escape the security
        // scope; the managed folder now owns its own copy, so drop the staging file rather than
        // leaving a duplicate of every imported book behind until the OS decides to sweep tmp.
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        feed.publish(result)
    }
}

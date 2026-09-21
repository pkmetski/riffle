package com.riffle.shared.audiobook

import com.riffle.core.domain.IosZipArchive
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Makes a Storyteller readaloud bundle's audio playable by AVFoundation (ADR 0027).
 *
 * A bundle-backed [com.riffle.core.domain.AudiobookSession] carries zip-**entry** paths as its
 * track URLs (`buildBundleAudiobookSession` maps each Media Overlay clip's `audioSrc`) plus the
 * bundle's own path in `localZipFilePath`. Android routes those entry paths through a `zipaudio://`
 * scheme that `ZipAudioDataSource` serves straight out of the zip. AVFoundation has no equivalent
 * seam that does not mean writing an `AVAssetResourceLoaderDelegate`, so iOS materialises the
 * entries as files once and plays `file://` URLs.
 *
 * Without this the bundle's entry paths reached Swift unchanged and `URL(string:)` produced a
 * relative URL with no scheme; `AVPlayerItem` accepted it, never loaded, and the book played
 * silence — the bug in #1072 §3.
 */
interface BundleAudioExtractor {
    /**
     * Returns a playable `file://` URL for each of [entryPaths], in the same order, extracting
     * from the zip at [zipFilePath] only the entries not already on disk. An entry that is missing
     * from the archive is dropped, so a caller receiving fewer URLs than it asked for knows the
     * bundle is incomplete and must not queue a partial book against the full span list.
     */
    suspend fun extractedTrackUrls(zipFilePath: String, entryPaths: List<String>): List<String>
}

/**
 * The real extractor, over [IosZipArchive] (the same reader the Media Overlay parser uses) and
 * `NSCachesDirectory`.
 *
 * Extracted audio lands in `Caches/riffle-bundle-audio/<bundle name>-<bundle size>/…`, so:
 * re-opening the same book reuses the files (no re-extraction), a re-downloaded bundle of a
 * different size gets a different directory rather than stale audio, and the OS may evict the
 * whole tree under disk pressure — after which the next open simply extracts again. The bundle
 * itself is the durable copy; this is a derived cache.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBundleAudioExtractor : BundleAudioExtractor {

    override suspend fun extractedTrackUrls(zipFilePath: String, entryPaths: List<String>): List<String> {
        if (entryPaths.isEmpty()) return emptyList()
        val manager = NSFileManager.defaultManager
        val bundleData = NSData.dataWithContentsOfFile(zipFilePath) ?: return emptyList()
        val dir = cacheDirFor(zipFilePath, bundleData.length.toLong())
        manager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)

        // Parse the central directory only if at least one entry is actually missing — the common
        // case (second open of the same book) then costs a handful of `fileExistsAtPath` calls.
        val targets = entryPaths.map { it to "$dir/${cacheFileName(it)}" }
        val missing = targets.filterNot { (_, path) -> manager.fileExistsAtPath(path) }
        if (missing.isNotEmpty()) {
            val archive = IosZipArchive(bundleData.toByteArray())
            for ((entry, path) in missing) {
                val bytes = archive.readEntry(entry) ?: continue
                bytes.writeTo(path)
            }
        }

        return targets.mapNotNull { (_, path) ->
            if (manager.fileExistsAtPath(path)) NSURL.fileURLWithPath(path).absoluteString else null
        }
    }

    private fun cacheDirFor(zipFilePath: String, sizeBytes: Long): String {
        val caches = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: TEMP_DIR_FALLBACK
        val bundleName = zipFilePath.substringAfterLast('/').replace('.', '_')
        return "$caches/$CACHE_NAMESPACE/$bundleName-$sizeBytes"
    }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) {
            out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), bytes, length) }
        }
        return out
    }

    private fun ByteArray.writeTo(path: String) {
        val data = if (isEmpty()) {
            NSData()
        } else {
            usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
        }
        data.writeToFile(path, atomically = true)
    }

    private companion object {
        const val CACHE_NAMESPACE = "riffle-bundle-audio"
        const val TEMP_DIR_FALLBACK = "/tmp"
    }
}

/**
 * A zip entry path flattened into one filename, keeping the extension so AVFoundation can sniff
 * the container from it. `"OEBPS/audio/part0003.mp4"` → `"OEBPS_audio_part0003.mp4"`.
 *
 * Shared with the tests: the flattening must be collision-free across a bundle's entries, which is
 * why separators are replaced rather than dropped.
 */
fun cacheFileName(entryPath: String): String {
    val normalised = entryPath.trimStart('/')
    val extension = normalised.substringAfterLast('.', "")
    val stem = if (extension.isEmpty()) normalised else normalised.dropLast(extension.length + 1)
    val safeStem = stem.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
    return if (extension.isEmpty()) safeStem else "$safeStem.$extension"
}

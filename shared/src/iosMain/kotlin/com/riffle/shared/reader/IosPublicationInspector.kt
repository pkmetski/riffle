package com.riffle.shared.reader

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Obj-C-compatible seam for headless EPUB inspection — opening a Readium Swift [Publication]
 * just to read its table of contents and position count, without instantiating a navigator or
 * view controller (unlike [IosEpubNavigatorBridge], which always hosts a live reader UI).
 *
 * Swift implementation: ReadiumPublicationInspector (in iosApp/iosApp/).
 * Registered at startup via a single instance passed to startKoin() — unlike the navigator/audio
 * bridges, one stateless inspector instance serves every call; there is no per-book session.
 *
 * [onResult] is invoked exactly once, on the main thread, with a JSON object
 * `{"tocJson":"[...]","totalPositions":42}` (same TOC schema as [IosEpubNavigatorBridge.getTocJson])
 * on success, or `null` if the EPUB could not be opened/parsed.
 */
interface IosPublicationInspector {
    fun inspectEpub(filePath: String, onResult: (resultJson: String?) -> Unit)

    /**
     * Resolves a whole-book progression (0.0..1.0) to a Locator, the **fallback** inbound-sync
     * path for when the server gives us a bare `ebookProgress` float and no usable `ebookLocation`
     * CFI (ADR-0013's primary path).
     *
     * Backed by Readium Swift's `Publication.locate(progression:)`, the counterpart of Android's
     * `Publication.locateProgression(totalProgression)`. The resulting Locator names the spine item
     * the progression falls in *and* carries the within-chapter progression, so a position received
     * as a bare float still lands where the reader left off instead of at the chapter start.
     *
     * [onResult] is invoked exactly once, on the main thread, with the Locator JSON (the same
     * schema [IosEpubNavigatorBridge.openEpub] accepts as `locatorJson`), or `null` when the EPUB
     * cannot be opened or the progression cannot be resolved.
     */
    fun locateProgression(
        filePath: String,
        totalProgression: Double,
        onResult: (locatorJson: String?) -> Unit,
    )
}

/**
 * Suspending wrapper over [IosPublicationInspector.locateProgression] for the reader's open path.
 *
 * Returns null — meaning "open at the start, as before" — for a progression that carries no
 * information (0, or outside 0..1), so the fallback never costs a publication open when there is
 * nothing to resolve.
 */
internal suspend fun locatorForProgression(
    inspector: IosPublicationInspector,
    filePath: String,
    totalProgression: Double,
): String? {
    if (totalProgression <= 0.0 || totalProgression > 1.0) return null
    return suspendCancellableCoroutine { cont ->
        inspector.locateProgression(filePath, totalProgression) { locatorJson -> cont.resume(locatorJson) }
    }
}

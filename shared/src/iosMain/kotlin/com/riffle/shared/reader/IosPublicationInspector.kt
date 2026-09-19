package com.riffle.shared.reader

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
}

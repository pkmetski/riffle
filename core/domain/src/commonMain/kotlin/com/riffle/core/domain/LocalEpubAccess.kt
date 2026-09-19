package com.riffle.core.domain

/**
 * Where a matched book's EPUBs are on disk, and how to read them — the two seams that let
 * `ReaderSyncFactory` (ADR 0023) be shared.
 *
 * Paths rather than bytes, because the Storyteller side is the synced bundle (ADR 0027) and can be
 * hundreds of MB: Android's implementation streams the file through the digest and pulls only the
 * OPF/spine/SMIL entries out of the zip, and must keep doing so.
 */
interface LocalEpubLocator {
    /** Downloaded-or-cached EPUB for (sourceId, itemId), or null when it is not stored locally. */
    fun localEpubPath(sourceId: String, itemId: String): String?

    /**
     * The prepared readaloud sidecar for a Storyteller book (ADR 0040) — the audio-free stand-in
     * used when the full bundle has not been downloaded. Null when it has not been prepared.
     */
    fun sidecarEpubPath(storytellerSourceId: String, storytellerBookId: String): String?
}

/** Reads an EPUB at a path: its checksum (the cross-EPUB index key) and its spine/overlay content. */
interface EpubAnalyzer {
    fun checksum(path: String): String?
    fun extract(path: String): ExtractedEpub?
}

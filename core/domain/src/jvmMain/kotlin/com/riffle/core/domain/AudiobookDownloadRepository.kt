package com.riffle.core.domain

/**
 * JVM extension of [AudiobookDownloadRepository] that exposes [localSession] — backed by
 * [AudiobookSession] which carries a [java.io.File] ref and therefore cannot be in commonMain.
 */
interface JvmAudiobookDownloadRepository : AudiobookDownloadRepository {
    /** A playable session backed by the downloaded local files (`file://` track URLs), or null. */
    fun localSession(sourceId: String, itemId: String): AudiobookSession?
}

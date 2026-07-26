package com.riffle.core.sync

import com.riffle.core.domain.ProgressRemote

/** Builds the per-target [ProgressRemote] for one (sourceId, itemId), or null when unavailable. */
interface ProgressRemoteFactory {
    suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String>?
    suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double>?
}

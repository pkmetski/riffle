package com.riffle.feature.reader

import kotlinx.coroutines.flow.Flow

/**
 * Platform-neutral abstraction over the Storyteller sidecar cache and streaming-session builder.
 *
 * Android implements this via `ReadaloudSidecarStore` + `ReadaloudStreamingSessionFactory`.
 * iOS returns no-ops: bundles are always fully downloaded on iOS, so streaming is never built.
 */
interface ReadaloudSidecarDelegate {
    /** Live state of every sidecar keyed by [key]. Collected by the session's sidecar observer. */
    val states: Flow<Map<String, ReadaloudSidecarState>>

    /** Canonical cache key for [sourceId]/[itemId]. */
    fun key(sourceId: String, itemId: String): String

    /** Absolute path of the cached sidecar file, or null when not yet cached. */
    fun cachedBundlePath(sourceId: String, itemId: String): String?

    /**
     * Starts or resumes fetching the sidecar for [sourceId]/[itemId]. No-op when already cached
     * or when the platform doesn't support sidecar streaming.
     */
    fun prepare(sourceId: String, itemId: String)

    /** Tries to build a streaming session from the cached sidecar. Null = fall back to bundle download. */
    suspend fun tryBuildStreamingSession(sourceId: String, itemId: String): ReadaloudStreamingSession?

    /** Removes a specific sidecar. Used by [tryBuildStreamingSession] to evict stale partial files. */
    fun remove(sourceId: String, itemId: String)
}

enum class ReadaloudSidecarState { Preparing, Ready, Failed }

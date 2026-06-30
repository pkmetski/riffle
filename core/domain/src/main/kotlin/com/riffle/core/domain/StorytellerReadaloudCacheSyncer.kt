package com.riffle.core.domain

/**
 * Best-effort background refresh of the Storyteller readaloud catalogue (per Server, throttled by
 * a TTL). Failures are swallowed; the next call retries.
 */
interface StorytellerReadaloudCacheSyncer {
    suspend fun syncStale()
}

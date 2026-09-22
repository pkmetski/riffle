package com.riffle.core.domain

/**
 * Records which audiobooks were most recently stopped by the sleep timer, not by an explicit user
 * pause. The ViewModel marks an item stopped when the timer fires and reads the flag on the next
 * open to suppress auto-play, then clears it so subsequent opens resume normally.
 */
interface AudiobookSleepStopStore {
    suspend fun markSleepStopped(sourceId: String, itemId: String)
    suspend fun clearSleepStopped(sourceId: String, itemId: String)
    suspend fun wasSleepStopped(sourceId: String, itemId: String): Boolean
}

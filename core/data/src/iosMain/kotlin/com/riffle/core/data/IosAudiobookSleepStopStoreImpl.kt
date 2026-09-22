package com.riffle.core.data

import com.riffle.core.domain.AudiobookSleepStopStore
import platform.Foundation.NSUserDefaults

internal class IosAudiobookSleepStopStoreImpl : AudiobookSleepStopStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun markSleepStopped(sourceId: String, itemId: String) {
        val current = current()
        defaults.setObject(current + key(sourceId, itemId), forKey = DEFAULTS_KEY)
    }

    override suspend fun clearSleepStopped(sourceId: String, itemId: String) {
        val updated = current() - key(sourceId, itemId)
        defaults.setObject(updated, forKey = DEFAULTS_KEY)
    }

    override suspend fun wasSleepStopped(sourceId: String, itemId: String): Boolean =
        current().contains(key(sourceId, itemId))

    @Suppress("UNCHECKED_CAST")
    private fun current(): Set<String> =
        (defaults.objectForKey(DEFAULTS_KEY) as? Set<String>).orEmpty()

    private fun key(sourceId: String, itemId: String) = "$sourceId/$itemId"

    private companion object {
        const val DEFAULTS_KEY = "audiobook_sleep_stopped"
    }
}

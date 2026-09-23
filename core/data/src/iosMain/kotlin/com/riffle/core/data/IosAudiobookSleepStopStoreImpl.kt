package com.riffle.core.data

import com.riffle.core.domain.AudiobookSleepStopStore
import platform.Foundation.NSUserDefaults

internal class IosAudiobookSleepStopStoreImpl : AudiobookSleepStopStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun markSleepStopped(sourceId: String, itemId: String) {
        val updated = current() + key(sourceId, itemId)
        defaults.setObject(updated.toList(), forKey = DEFAULTS_KEY)
    }

    override suspend fun clearSleepStopped(sourceId: String, itemId: String) {
        val updated = current() - key(sourceId, itemId)
        defaults.setObject(updated.toList(), forKey = DEFAULTS_KEY)
    }

    override suspend fun wasSleepStopped(sourceId: String, itemId: String): Boolean =
        current().contains(key(sourceId, itemId))

    private fun current(): Set<String> =
        defaults.stringArrayForKey(DEFAULTS_KEY)?.filterIsInstance<String>()?.toSet().orEmpty()

    private fun key(sourceId: String, itemId: String) = "$sourceId/$itemId"

    private companion object {
        const val DEFAULTS_KEY = "audiobook_sleep_stopped"
    }
}

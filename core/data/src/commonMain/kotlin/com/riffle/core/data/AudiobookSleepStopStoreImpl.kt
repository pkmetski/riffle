package com.riffle.core.data

import com.riffle.core.domain.AudiobookSleepStopStore

class AudiobookSleepStopStoreImpl(private val store: KeyValueStore) : AudiobookSleepStopStore {

    override suspend fun markSleepStopped(sourceId: String, itemId: String) {
        val updated = store.getStringSet(KEY) + entryKey(sourceId, itemId)
        store.setStringSet(KEY, updated)
    }

    override suspend fun clearSleepStopped(sourceId: String, itemId: String) {
        val updated = store.getStringSet(KEY) - entryKey(sourceId, itemId)
        store.setStringSet(KEY, updated)
    }

    override suspend fun wasSleepStopped(sourceId: String, itemId: String): Boolean =
        store.getStringSet(KEY).contains(entryKey(sourceId, itemId))

    private fun entryKey(sourceId: String, itemId: String) = "$sourceId/$itemId"

    private companion object {
        const val KEY = "audiobook_sleep_stopped"
    }
}

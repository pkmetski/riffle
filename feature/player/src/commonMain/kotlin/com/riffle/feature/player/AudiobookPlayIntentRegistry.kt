package com.riffle.feature.player

class AudiobookPlayIntentRegistry {
    private val pending = mutableSetOf<String>()

    fun recordIntent(sourceId: String, itemId: String) {
        pending.add(key(sourceId, itemId))
    }

    fun consumeIntent(sourceId: String, itemId: String): Boolean =
        pending.remove(key(sourceId, itemId))

    private fun key(s: String, i: String) = "$s/$i"
}

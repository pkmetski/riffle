package com.riffle.core.domain

import kotlinx.serialization.json.Json

/**
 * Serialises a [CrossEpubIndex] to and from the `perChapterMapsBlob` string persisted in
 * the `cross_epub_index` Room table. [decode] returns `null` for a malformed blob so a
 * corrupt cache row degrades to a rebuild rather than crashing a sync cycle.
 */
object CrossEpubIndexSerializer {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(index: CrossEpubIndex): String =
        json.encodeToString(CrossEpubIndex.serializer(), index)

    fun decode(blob: String): CrossEpubIndex? =
        try {
            json.decodeFromString<CrossEpubIndex>(blob)
        } catch (_: Exception) {
            null
        }
}

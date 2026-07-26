package com.riffle.core.database

import androidx.room.Entity

/**
 * Cached cross-EPUB character-position index for a matched readaloud book (ADR 0019).
 *
 * Keyed by the two source EPUBs' checksums: a row is a hit only while both the ABS EPUB
 * and the Storyteller EPUB are byte-for-byte what the index was built from. If either
 * server re-uploads its EPUB, its checksum changes, the keyed lookup misses, and the next
 * sync cycle rebuilds — so the table needs no explicit invalidation.
 *
 * [perChapterMapsBlob] is the serialised per-chapter character maps (the domain
 * `CrossEpubIndex`). This table is a pure local cache: no foreign keys, cleared freely.
 */
@Entity(
    tableName = "cross_epub_index",
    primaryKeys = ["absEpubChecksum", "storytellerEpubChecksum"],
)
data class CrossEpubIndexEntity(
    val absEpubChecksum: String,
    val storytellerEpubChecksum: String,
    val perChapterMapsBlob: String,
    val builtAt: Long,
)

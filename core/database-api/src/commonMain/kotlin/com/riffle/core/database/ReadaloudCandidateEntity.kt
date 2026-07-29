package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A Tier 3 (fuzzy) ABS candidate for a Storyteller readaloud, awaiting the user's review
 * decision (ADR 0021). One row per (readaloud, ABS item) pair the auto-matcher surfaced;
 * [score] is the matcher's combined title/author similarity.
 *
 * Keyed on the full (Storyteller, ABS) pair because a single readaloud can have several
 * fuzzy candidates and a single ABS item could (in principle) be a candidate for more than
 * one readaloud. Both source columns FK-cascade to `sources.id` so candidates vanish when
 * either side's Source is removed.
 */
@Entity(
    tableName = "readaloud_candidates",
    primaryKeys = ["storytellerSourceId", "storytellerBookId", "absSourceId", "absLibraryItemId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["storytellerSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["absSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // The storyteller FK is covered by the PK prefix; the ABS FK needs its own index.
        Index(value = ["absSourceId"]),
    ],
)
data class ReadaloudCandidateEntity(
    val storytellerSourceId: String,
    val storytellerBookId: String,
    val absSourceId: String,
    val absLibraryItemId: String,
    val score: Double,
)
